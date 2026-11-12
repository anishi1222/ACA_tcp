package com.example.tcp.api;

import java.time.Duration;
import java.util.Map;

import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppConfigTest {

    @Test
    void bindsAndTrimsTheTcpServerHost() {
        try (var context = ApplicationContext.run(Map.of("tcp.server.host", " app-a "))) {
            assertEquals("app-a", context.getBean(AppConfig.class).host());
        }
    }

    @Test
    void usesTheOriginalDefaultsWhenNoConfigurationIsProvided() {
        try (var context = ApplicationContext.run()) {
            var config = context.getBean(AppConfig.class);

            assertEquals("localhost", config.host());
            assertEquals(Duration.ofSeconds(3), config.connectTimeout());
            assertEquals(Duration.ofSeconds(5), config.readTimeout());
        }
    }

    @Test
    void fallsBackToLocalhostWhenTheHostIsBlank() {
        try (var context = ApplicationContext.run(Map.of("tcp.server.host", "  "))) {
            assertEquals("localhost", context.getBean(AppConfig.class).host());
        }
    }

    @Test
    void bindsTimeoutOverrides() {
        try (var context = ApplicationContext.run(Map.of(
                "tcp.server.connect-timeout", "200ms",
                "tcp.server.read-timeout", "300ms"))) {
            var config = context.getBean(AppConfig.class);

            assertEquals(Duration.ofMillis(200), config.connectTimeout());
            assertEquals(Duration.ofMillis(300), config.readTimeout());
        }
    }
}
