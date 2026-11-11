package com.example.tcp.api;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Minimal line-oriented TCP client: connects, sends one line and reads one line back.
 *
 * <p>The client never half-closes its side of the connection. It just closes the socket after the reply, which keeps
 * it compatible with TCP proxies that treat a half-close as the end of the whole connection.
 */
final class TcpLineClient {

    static final int MAX_REPLY_BYTES = 8 * 1024;

    private final String host;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;

    TcpLineClient(String host, Duration connectTimeout, Duration readTimeout) {
        this.host = host;
        this.connectTimeoutMillis = Math.toIntExact(connectTimeout.toMillis());
        this.readTimeoutMillis = Math.toIntExact(readTimeout.toMillis());
    }

    String host() {
        return host;
    }

    /**
     * Sends {@code message} terminated by a line feed to {@code host:port} and returns the first reply line.
     *
     * @throws java.net.SocketTimeoutException if connecting or waiting for the reply takes too long
     * @throws IOException                     on any other failure, including a connection closed without a reply
     */
    String exchange(int port, String message) throws IOException {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMillis);
            socket.setSoTimeout(readTimeoutMillis);
            socket.setTcpNoDelay(true);
            socket.getOutputStream().write((message + "\n").getBytes(UTF_8));
            return readLine(new BufferedInputStream(socket.getInputStream()));
        }
    }

    /** Reads one line without its terminator; an unterminated last line is accepted. */
    static String readLine(InputStream in) throws IOException {
        var line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1 && b != '\n') {
            if (line.size() >= MAX_REPLY_BYTES) {
                throw new IOException("Reply line exceeds " + MAX_REPLY_BYTES + " bytes");
            }
            line.write(b);
        }
        if (b == -1 && line.size() == 0) {
            throw new IOException("Connection closed before a reply was received");
        }
        var text = line.toString(UTF_8);
        return text.endsWith("\r") ? text.substring(0, text.length() - 1) : text;
    }
}
