package com.example.tcp.api;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiServerTest {

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private ApiServer api;

    @BeforeEach
    void startApi() throws IOException {
        api = ApiServer.start(config(Duration.ofSeconds(2)));
    }

    @AfterEach
    void stopApi() {
        // Stop the server while the client's keep-alive connection is still idle. If the client hangs up first, the
        // JDK server may see that EOF as a request in progress and make stop() wait for its whole grace period.
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

    @ParameterizedTest
    @ValueSource(strings = {
            "", "?", "?port", "?port=", "?port=abc", "?port=0", "?port=65536", "?port=-1", "?port=1.5", "?port=%20",
            "?port=123456", "?other=18192"})
    void rejectsAMissingOrInvalidPortWith400(String query) throws Exception {
        assertEquals(400, get("/api/tcp" + query).statusCode());
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
             var impatientApi = ApiServer.start(config(Duration.ofMillis(300)))) {
            var response = send(impatientApi, "GET", "/api/tcp?port=" + tcp.port());

            assertEquals(504, response.statusCode());
        }
    }

    @Test
    void rejectsMethodsOtherThanGetWith405() throws Exception {
        var response = send(api, "POST", "/api/tcp?port=18192");

        assertEquals(405, response.statusCode());
        assertEquals("GET", response.headers().firstValue("Allow").orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/api", "/api/other", "/api/tcpx", "/api/tcp/extra"})
    void answers404OutsideTheContextPath(String path) throws Exception {
        assertEquals(404, get(path + "?port=18192").statusCode());
    }

    private static AppConfig config(Duration readTimeout) {
        return new AppConfig("127.0.0.1", 0, Duration.ofSeconds(2), readTimeout);
    }

    private HttpResponse<String> get(String pathAndQuery) throws Exception {
        return send(api, "GET", pathAndQuery);
    }

    private HttpResponse<String> send(ApiServer server, String method, String pathAndQuery) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + pathAndQuery))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
