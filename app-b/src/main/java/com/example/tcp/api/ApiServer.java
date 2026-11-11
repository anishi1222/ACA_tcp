package com.example.tcp.api;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpServer;

import static java.lang.System.Logger.Level.INFO;

/** The HTTP server of container app B. Requests are handled on virtual threads. */
final class ApiServer implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(ApiServer.class.getName());
    private static final int SHUTDOWN_GRACE_SECONDS = 5;

    private final HttpServer http;
    private final ExecutorService executor;

    private ApiServer(HttpServer http, ExecutorService executor) {
        this.http = http;
        this.executor = executor;
    }

    static ApiServer start(AppConfig config) throws IOException {
        var http = HttpServer.create(new InetSocketAddress(config.httpPort()), 0);
        var executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("http-", 0).factory());
        var client = new TcpLineClient(config.tcpServerHost(), config.connectTimeout(), config.readTimeout());
        http.createContext(TcpApiHandler.CONTEXT_PATH, new TcpApiHandler(client));
        http.setExecutor(executor);
        http.start();

        var server = new ApiServer(http, executor);
        LOG.log(INFO, "Listening on HTTP port " + server.port() + ", context path " + TcpApiHandler.CONTEXT_PATH
                + ", TCP server host " + config.tcpServerHost());
        return server;
    }

    int port() {
        return http.getAddress().getPort();
    }

    /** Waits briefly for requests in flight, then drops whatever is left. */
    @Override
    public void close() {
        http.stop(SHUTDOWN_GRACE_SECONDS);
        executor.shutdownNow();
    }
}
