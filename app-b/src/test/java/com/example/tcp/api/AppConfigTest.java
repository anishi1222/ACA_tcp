package com.example.tcp.api;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppConfigTest {

    @Test
    void readsTheTcpServerHostFromTheEnvironment() {
        var config = AppConfig.fromEnv(Map.of("TCP_SERVER_HOST", " app-a "));

        assertEquals("app-a", config.tcpServerHost());
    }

    @Test
    void fallsBackToLocalhostWhenTheVariableIsMissingOrBlank() {
        assertEquals("localhost", AppConfig.fromEnv(Map.of()).tcpServerHost());
        assertEquals("localhost", AppConfig.fromEnv(Map.of("TCP_SERVER_HOST", "  ")).tcpServerHost());
    }
}
