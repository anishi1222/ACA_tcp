package com.example.tcp.echo;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

public final class Main {

    private static final List<Integer> PORTS = List.of(18192, 28192, 38192);
    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(60);
    private static final String LOG_FORMAT_KEY = "java.util.logging.SimpleFormatter.format";

    private Main() {
    }

    static void main() throws IOException {
        // One line per record keeps container log streams readable.
        System.setProperty(LOG_FORMAT_KEY,
                System.getProperty(LOG_FORMAT_KEY, "%1$tFT%1$tT.%1$tL%1$tz %4$-7s [%3$s] %5$s%6$s%n"));

        var server = EchoServer.start(PORTS, IDLE_TIMEOUT);
        // The accept threads are non-daemon, so the JVM stays up after main returns.
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().name("shutdown").unstarted(server::close));
    }
}
