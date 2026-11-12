package com.example.tcp.api;

import java.io.IOException;

public final class Main {

    private static final String LOG_FORMAT_KEY = "java.util.logging.SimpleFormatter.format";

    private Main() {
    }

    static void main() throws IOException {
        // One line per record keeps container log streams readable. Must be set before the first logger is used.
        System.setProperty(LOG_FORMAT_KEY,
                System.getProperty(LOG_FORMAT_KEY, "%1$tFT%1$tT.%1$tL%1$tz %4$-7s [%3$s] %5$s%6$s%n"));

        try (var server = ApiServer.start(AppConfig.fromEnv(System.getenv()))) {
            // The HTTP dispatcher thread is non-daemon, so the JVM stays up after main returns.
            Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().name("shutdown").unstarted(server::close));
        }
    }
}
