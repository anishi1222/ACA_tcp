package com.example.tcp.api;

import java.time.Duration;
import java.util.Map;

/**
 * Runtime configuration of the HTTP API.
 *
 * @param tcpServerHost host name or IP address of the TCP server (container app A)
 * @param httpPort      HTTP listening port; 0 picks an ephemeral port, which only the tests use
 */
record AppConfig(String tcpServerHost, int httpPort, Duration connectTimeout, Duration readTimeout) {

    static final String TCP_SERVER_HOST_ENV = "TCP_SERVER_HOST";
    static final String DEFAULT_TCP_SERVER_HOST = "localhost";
    static final int HTTP_PORT = 8080;

    static AppConfig fromEnv(Map<String, String> env) {
        var host = env.getOrDefault(TCP_SERVER_HOST_ENV, "").strip();
        return new AppConfig(
                host.isEmpty() ? DEFAULT_TCP_SERVER_HOST : host,
                HTTP_PORT,
                Duration.ofSeconds(3),
                Duration.ofSeconds(5));
    }
}
