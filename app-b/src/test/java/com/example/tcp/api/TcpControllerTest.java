package com.example.tcp.api;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TcpControllerTest {

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private EmbeddedServer api;

    @BeforeEach
    void startApi() {
        api = startServer(Duration.ofSeconds(2));
    }

    @AfterEach
    void stopApi() {
        api.close();
        httpClient.close();
    }

    @Test
    void sendsHelloToTheRequestedPortAndReturnsTheReply() throws Exception {
        try (var tcp = FakeTcpServer.replying()) {
            var response = get("/api/tcp?port=" + tcp.port());

            assertEquals(200, response.statusCode());
            assertEquals("reply to: Hello " + tcp.port() + ", how are you?", response.body());
            assertEquals("text/plain; charset=utf-8", response.headers().firstValue("Content-Type").orElseThrow());
            assertEquals("Hello " + tcp.port() + ", how are you?", tcp.awaitRequest());
        }
    }

    @Test
    void preservesUtf8InTheTcpReply() throws Exception {
        var reply = "\u3053\u3093\u306b\u3061\u306f\u3001\u4e16\u754c";
        try (var tcp = FakeTcpServer.replying(_ -> reply)) {
            var response = get("/api/tcp?port=" + tcp.port());

            assertEquals(200, response.statusCode());
            assertEquals(reply, response.body());
            assertEquals("text/plain; charset=utf-8", response.headers().firstValue("Content-Type").orElseThrow());
        }
    }

    @Test
    void acceptsATrailingSlashInThePath() throws Exception {
        try (var tcp = FakeTcpServer.replying()) {
            assertEquals(200, get("/api/tcp/?port=" + tcp.port()).statusCode());
        }
    }

    @Test
    void usesTheFirstPortParameterWhenSeveralAreGiven() throws Exception {
        try (var tcp = FakeTcpServer.replying()) {
            assertEquals(200, get("/api/tcp?port=" + tcp.port() + "&port=1").statusCode());
        }
    }

    @Test
    void decodesQueryParameterNamesAndValues() throws Exception {
        try (var tcp = FakeTcpServer.replying()) {
            var port = Integer.toString(tcp.port());
            var encodedPort = "%" + Integer.toHexString(port.charAt(0)) + port.substring(1);

            var response = get("/api/tcp?other=value&p%6Frt=" + encodedPort);

            assertEquals(200, response.statusCode());
            assertEquals("Hello " + tcp.port() + ", how are you?", tcp.awaitRequest());
        }
    }

    @Test
    void rejectsAnInvalidFirstPortEvenWhenTheSecondOneIsValid() throws Exception {
        assertEquals(400, get("/api/tcp?port=abc&port=18192").statusCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "?", "?port", "?port=", "?port=abc", "?port=0", "?port=65536", "?port=-1", "?port=1.5", "?port=%20",
            "?port=123456", "?port=+42", "?port=%2B42", "?port=%0A123", "?other=18192"})
    void rejectsAMissingOrInvalidPortWith400(String query) throws Exception {
        var response = get("/api/tcp" + query);

        assertEquals(400, response.statusCode());
        assertEquals("Query parameter 'port' is required and must be an integer from 1 to 65535", response.body());
        assertEquals("text/plain; charset=utf-8", response.headers().firstValue("Content-Type").orElseThrow());
    }

    @Test
    void reportsBadGatewayWhenTheTcpServerRefusesTheConnection() throws Exception {
        int unusedPort;
        try (var probe = new ServerSocket(0)) {
            unusedPort = probe.getLocalPort();
        }

        var response = get("/api/tcp?port=" + unusedPort);

        assertEquals(502, response.statusCode());
        assertTrue(response.body().contains("127.0.0.1:" + unusedPort), response.body());
    }

    @Test
    void reportsGatewayTimeoutWhenTheTcpServerDoesNotAnswer() throws Exception {
        try (var tcp = FakeTcpServer.silent();
             var impatientApi = startServer(Duration.ofMillis(300))) {
            var response = send(impatientApi, "GET", "/api/tcp?port=" + tcp.port());

            assertEquals(504, response.statusCode());
        }
    }

    @Test
    void reportsBadGatewayWhenTheTcpServerClosesWithoutAReply() throws Exception {
        try (var tcp = FakeTcpServer.replying(_ -> null)) {
            var response = get("/api/tcp?port=" + tcp.port());

            assertEquals(502, response.statusCode());
            assertTrue(response.body().contains("Connection closed before a reply was received"), response.body());
        }
    }

    @Test
    void reportsBadGatewayWhenTheReplyIsTooLong() throws Exception {
        try (var tcp = FakeTcpServer.replying(_ -> "x".repeat(TcpLineClient.MAX_REPLY_BYTES + 1))) {
            assertEquals(502, get("/api/tcp?port=" + tcp.port()).statusCode());
        }
    }

    @Test
    void returnsAnEmptyReplyWith200() throws Exception {
        try (var tcp = FakeTcpServer.replying(_ -> "")) {
            var response = get("/api/tcp?port=" + tcp.port());

            assertEquals(200, response.statusCode());
            assertEquals("", response.body());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE"})
    void rejectsMethodsOtherThanGetWith405(String method) throws Exception {
        var response = send(api, method, "/api/tcp?port=18192");

        assertEquals(405, response.statusCode());
        assertEquals("GET", response.headers().firstValue("Allow").orElseThrow());
        assertEquals("text/plain; charset=utf-8", response.headers().firstValue("Content-Type").orElseThrow());
        assertEquals(method.equals("HEAD") ? "" : "Method Not Allowed", response.body());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/api", "/api/other", "/api/tcpx", "/api/tcp/extra", "/api/tcp//", "/api/TCP"})
    void answers404OutsideTheContextPath(String path) throws Exception {
        var response = get(path + "?port=18192");

        assertEquals(404, response.statusCode());
        assertEquals("Not Found", response.body());
        assertEquals("text/plain; charset=utf-8", response.headers().firstValue("Content-Type").orElseThrow());
    }

    private static EmbeddedServer startServer(Duration readTimeout) {
        return ApplicationContext.run(EmbeddedServer.class, Map.of(
                "micronaut.server.host", "127.0.0.1",
                "micronaut.server.port", -1,
                "tcp.server.host", "127.0.0.1",
                "tcp.server.connect-timeout", Duration.ofSeconds(2),
                "tcp.server.read-timeout", readTimeout));
    }

    private HttpResponse<String> get(String pathAndQuery) throws Exception {
        return send(api, "GET", pathAndQuery);
    }

    private HttpResponse<String> send(EmbeddedServer server, String method, String pathAndQuery) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getPort() + pathAndQuery))
                .timeout(Duration.ofSeconds(10))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
