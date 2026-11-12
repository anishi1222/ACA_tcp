package com.example.tcp.api;

import java.time.Duration;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.bind.annotation.Bindable;

@ConfigurationProperties("tcp.server")
public record AppConfig(
        @Bindable(defaultValue = "localhost") String host,
        @Bindable(defaultValue = "3s") Duration connectTimeout,
        @Bindable(defaultValue = "5s") Duration readTimeout) {

    public AppConfig {
        host = host.strip();
        if (host.isEmpty()) {
            host = "localhost";
        }
    }
}
