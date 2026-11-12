package com.example.tcp.echo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.BindException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EchoServerTest {

    private EchoServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = EchoServer.start(List.of(0, 0, 0), Duration.ofSeconds(10));
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void everyPortEchoesWithItsOwnPortNumber() throws IOException {
        assertEquals(3, Set.copyOf(server.ports()).size());
        for (int port : server.ports()) {
            try (var client = new Client(port)) {
                client.send("Hello " + port + ", how are you?\n");
                assertEquals("Acknowledged Hello " + port + ", how are you? This message is sent from " + port + "!",
                        client.readLine());
            }
        }
    }

    @Test
    void answersEveryLineOnOneConnectionAndAcceptsCrLf() throws IOException {
        int port = server.ports().getFirst();
        try (var client = new Client(port)) {
            client.send("one\r\ntwo\n");
            assertEquals("Acknowledged one This message is sent from " + port + "!", client.readLine());
            assertEquals("Acknowledged two This message is sent from " + port + "!", client.readLine());
        }
    }

    @Test
    void treatsLastLineWithoutTerminatorAsRequestAtEndOfStream() throws IOException {
        int port = server.ports().getFirst();
        try (var client = new Client(port)) {
            client.send("no newline");
            client.socket.shutdownOutput();
            assertEquals("Acknowledged no newline This message is sent from " + port + "!", client.readLine());
            assertTrue(client.isClosedByServer());
        }
    }

    @Test
    void closesConnectionWithoutReplyWhenRequestLineIsTooLong() throws IOException {
        try (var client = new Client(server.ports().getFirst())) {
            client.send("x".repeat(EchoServer.MAX_MESSAGE_BYTES + 1));
            assertTrue(client.isClosedByServer());
        }
    }

    @Test
    void closesIdleConnections() throws IOException {
        try (var idleServer = EchoServer.start(List.of(0), Duration.ofMillis(200));
             var client = new Client(idleServer.ports().getFirst())) {
            assertTrue(client.isClosedByServer());
        }
    }

    @Test
    void closeStopsListeningAndDropsOpenConnections() throws IOException {
        int port = server.ports().getFirst();
        try (var client = new Client(port)) {
            client.send("ping\n");
            client.readLine();

            server.close();

            assertTrue(client.isClosedByServer());
            assertThrows(ConnectException.class, () -> new Client(port));
        }
    }

    @Test
    void failsToStartAndReleasesBoundPortsWhenAPortIsTaken() throws IOException {
        try (var taken = new ServerSocket(0)) {
            int free;
            try (var probe = new ServerSocket(0)) {
                free = probe.getLocalPort();
            }

            assertThrows(BindException.class,
                    () -> EchoServer.start(List.of(free, taken.getLocalPort()), Duration.ofSeconds(1)));

            new ServerSocket(free).close();
        }
    }

    private static final class Client implements AutoCloseable {
        final Socket socket;
        private final BufferedReader reader;

        Client(int port) throws IOException {
            socket = new Socket(InetAddress.getLoopbackAddress(), port);
            socket.setSoTimeout(5_000);
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), UTF_8));
        }

        void send(String text) throws IOException {
            socket.getOutputStream().write(text.getBytes(UTF_8));
        }

        String readLine() throws IOException {
            return reader.readLine();
        }

        /** True once the server ended the connection (EOF or reset) without sending anything further. */
        boolean isClosedByServer() throws IOException {
            try {
                return reader.readLine() == null;
            } catch (SocketException e) {
                return e.getMessage().contains("reset");
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
