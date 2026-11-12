package com.example.tcp.echo;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.INFO;
import static java.lang.System.Logger.Level.WARNING;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Echo server that listens on several TCP ports at once.
 *
 * <p>Protocol: UTF-8 text, one request per line (LF or CRLF; a last line ended by EOF also counts).
 * Each request line is answered with one line, see {@link #formatReply(int, String)}. A connection may carry
 * any number of requests and is closed on EOF, on an idle timeout, or when a request line is too long.
 */
final class EchoServer implements AutoCloseable {

    static final int MAX_MESSAGE_BYTES = 8 * 1024;

    private static final System.Logger LOG = System.getLogger(EchoServer.class.getName());
    private static final int BACKLOG = 128;
    private static final int MAX_LOGGED_CHARS = 200;

    private final List<ServerSocket> listeners;
    private final int idleTimeoutMillis;
    private final ExecutorService connections =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("echo-conn-", 0).factory());
    private final AtomicBoolean closed = new AtomicBoolean();

    private EchoServer(List<ServerSocket> listeners, Duration idleTimeout) {
        this.listeners = listeners;
        this.idleTimeoutMillis = Math.toIntExact(idleTimeout.toMillis());
    }

    /**
     * Binds every port first and only then starts accepting, so that a port conflict fails the whole start-up.
     * Port 0 binds an ephemeral port (see {@link #ports()}).
     */
    static EchoServer start(List<Integer> ports, Duration idleTimeout) throws IOException {
        var listeners = new ArrayList<ServerSocket>();
        try {
            for (int port : ports) {
                var listener = new ServerSocket();
                listeners.add(listener);
                listener.setReuseAddress(true);
                listener.bind(new InetSocketAddress(port), BACKLOG);
            }
        } catch (IOException | RuntimeException e) {
            listeners.forEach(EchoServer::closeQuietly);
            throw e;
        }
        var server = new EchoServer(List.copyOf(listeners), idleTimeout);
        server.listeners.forEach(server::startAcceptor);
        return server;
    }

    /** The ports actually bound, in the order they were requested. */
    List<Integer> ports() {
        return listeners.stream().map(ServerSocket::getLocalPort).toList();
    }

    static String formatReply(int port, String message) {
        return "Acknowledged " + message + " This message is sent from " + port + "!";
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        listeners.forEach(EchoServer::closeQuietly);
        connections.shutdownNow();
        try {
            if (!connections.awaitTermination(5, TimeUnit.SECONDS)) {
                LOG.log(WARNING, "Timed out waiting for connections to terminate");
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private void startAcceptor(ServerSocket listener) {
        int port = listener.getLocalPort();
        Thread.ofPlatform().name("echo-accept-" + port).start(() -> acceptLoop(listener, port));
        LOG.log(INFO, "Listening on TCP port " + port);
    }

    private void acceptLoop(ServerSocket listener, int port) {
        while (!listener.isClosed()) {
            Socket socket;
            try {
                socket = listener.accept();
            } catch (IOException e) {
                if (listener.isClosed()) {
                    return;
                }
                LOG.log(ERROR, "accept() failed on port " + port, e);
                try {
                    // Back off after accept failures, not while waiting for connections.
                    //noinspection BusyWait
                    Thread.sleep(100);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                    return;
                }
                continue;
            }
            try {
                connections.execute(() -> serve(socket, port));
            } catch (RejectedExecutionException _) {
                closeQuietly(socket);
                return;
            }
        }
    }

    private void serve(Socket socket, int port) {
        try (socket) {
            socket.setSoTimeout(idleTimeoutMillis);
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            var remote = socket.getRemoteSocketAddress();
            var in = new BufferedInputStream(socket.getInputStream());
            var out = socket.getOutputStream();
            String message;
            while ((message = readMessage(in)) != null) {
                LOG.log(INFO, "[" + port + "] " + remote + " -> " + printable(message));
                out.write((formatReply(port, message) + "\n").getBytes(UTF_8));
            }
        } catch (SocketTimeoutException _) {
            LOG.log(DEBUG, "[" + port + "] closed idle connection");
        } catch (ProtocolException e) {
            LOG.log(WARNING, "[" + port + "] " + e.getMessage() + "; closing connection");
        } catch (IOException e) {
            if (!closed.get()) {
                LOG.log(DEBUG, "[" + port + "] connection ended: " + e);
            }
        }
    }

    /** Reads one request line without its terminator, or returns null at end of stream. */
    static String readMessage(InputStream in) throws IOException {
        var line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                return decode(line);
            }
            if (line.size() >= MAX_MESSAGE_BYTES) {
                throw new ProtocolException("Request line exceeds " + MAX_MESSAGE_BYTES + " bytes");
            }
            line.write(b);
        }
        return line.size() == 0 ? null : decode(line);
    }

    private static String decode(ByteArrayOutputStream line) {
        var text = line.toString(UTF_8);
        return text.endsWith("\r") ? text.substring(0, text.length() - 1) : text;
    }

    /** Keeps client-controlled text from forging log lines. */
    private static String printable(String text) {
        var shown = text.length() > MAX_LOGGED_CHARS ? text.substring(0, MAX_LOGGED_CHARS) + "..." : text;
        return shown.replaceAll("\\p{Cntrl}", "?");
    }

    private static void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException _) {
            // nothing useful left to do
        }
    }
}
