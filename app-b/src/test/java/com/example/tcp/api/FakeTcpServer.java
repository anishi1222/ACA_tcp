package com.example.tcp.api;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

import static java.nio.charset.StandardCharsets.UTF_8;

/** A line-based TCP server on an ephemeral port that records the first line of every connection and may answer it. */
final class FakeTcpServer implements AutoCloseable {

    private final ServerSocket listener = new ServerSocket(0);
    private final BlockingQueue<String> requests = new LinkedBlockingQueue<>();
    private final List<Socket> connections = new CopyOnWriteArrayList<>();
    private final UnaryOperator<String> responder;

    /** @param responder maps a request line to the reply line; returning null leaves the request unanswered */
    private FakeTcpServer(UnaryOperator<String> responder) throws IOException {
        this.responder = responder;
        Thread.startVirtualThread(this::acceptLoop);
    }

    static FakeTcpServer replying() throws IOException {
        return new FakeTcpServer(line -> "reply to: " + line);
    }

    /** Accepts connections and reads the request but never answers, so clients run into their read timeout. */
    static FakeTcpServer silent() throws IOException {
        return new FakeTcpServer(_ -> null);
    }

    int port() {
        return listener.getLocalPort();
    }

    /** The next request line received, or null if none arrives within 5 seconds. */
    String awaitRequest() throws InterruptedException {
        return requests.poll(5, TimeUnit.SECONDS);
    }

    @Override
    public void close() throws IOException {
        listener.close();
        connections.forEach(FakeTcpServer::closeQuietly);
    }

    private void acceptLoop() {
        try {
            while (true) {
                var socket = listener.accept();
                connections.add(socket);
                Thread.startVirtualThread(() -> serve(socket));
            }
        } catch (IOException _) {
            // listener closed
        }
    }

    private void serve(Socket socket) {
        try (socket) {
            var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), UTF_8));
            var line = reader.readLine();
            if (line == null) {
                return;
            }
            requests.add(line);
            var reply = responder.apply(line);
            if (reply != null) {
                socket.getOutputStream().write((reply + "\n").getBytes(UTF_8));
            } else {
                reader.readLine();
            }
        } catch (IOException _) {
            // client went away
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException _) {
            // nothing useful left to do
        }
    }
}
