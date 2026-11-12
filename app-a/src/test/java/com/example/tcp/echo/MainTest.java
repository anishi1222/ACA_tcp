package com.example.tcp.echo;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainTest {

    @Test
    void keepsListeningAfterMainReturns() throws Exception {
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var process = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), Main.class.getName())
                .redirectErrorStream(true)
                .start();
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            var startup = executor.submit(() -> {
                var output = process.inputReader(UTF_8);
                int listeners = 0;
                String line;
                while (listeners < 3 && (line = output.readLine()) != null) {
                    if (line.contains("Listening on TCP port ")) {
                        listeners++;
                    }
                }
                return listeners;
            });
            assertEquals(3, startup.get(10, TimeUnit.SECONDS));
            assertFalse(process.waitFor(200, TimeUnit.MILLISECONDS), "Server exited after main returned");

            for (int port : List.of(18192, 28192, 38192)) {
                try (var socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
                    socket.setSoTimeout(5_000);
                    socket.getOutputStream().write("ping\n".getBytes(UTF_8));
                    var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), UTF_8));
                    assertEquals(EchoServer.formatReply(port, "ping"), reader.readLine());
                }
            }
        } finally {
            process.destroy();
            executor.shutdownNow();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Server process did not terminate");
            }
        }
    }
}
