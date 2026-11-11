package com.example.tcp.api;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.util.OptionalInt;
import java.util.regex.Pattern;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.INFO;
import static java.lang.System.Logger.Level.WARNING;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Handles {@code GET /api/tcp?port=N}: sends {@code Hello N, how are you?} to the TCP server on port N and returns
 * the reply as plain text.
 *
 * <p>Failures are reported as HTTP status codes: 400 for a missing or invalid port, 404 for other paths, 405 for
 * other methods, 502 when the TCP server cannot be reached or does not answer properly, 504 when it takes too long.
 */
final class TcpApiHandler implements HttpHandler {

    static final String CONTEXT_PATH = "/api/tcp";

    private static final System.Logger LOG = System.getLogger(TcpApiHandler.class.getName());
    private static final Pattern DIGITS = Pattern.compile("[0-9]{1,5}");
    private static final int MAX_LOGGED_CHARS = 200;

    private record Response(int status, String body) {
    }

    private final TcpLineClient client;

    TcpApiHandler(TcpLineClient client) {
        this.client = client;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            long startedAt = System.nanoTime();
            Response response;
            try {
                response = route(exchange);
            } catch (RuntimeException e) {
                LOG.log(ERROR, "Unexpected error while handling a request", e);
                response = new Response(500, "Internal Server Error");
            }
            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;
            LOG.log(INFO, response.status() + " " + describeRequest(exchange) + " (" + elapsedMillis + " ms)");
            send(exchange, response);
        }
    }

    private Response route(HttpExchange exchange) {
        // The JDK matches contexts by prefix, so /api/tcpx and /api/tcp/x would arrive here as well.
        var path = exchange.getRequestURI().getPath();
        if (!path.equals(CONTEXT_PATH) && !path.equals(CONTEXT_PATH + "/")) {
            return new Response(404, "Not Found");
        }
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "GET");
            return new Response(405, "Method Not Allowed");
        }
        var port = parsePort(exchange.getRequestURI().getRawQuery());
        if (port.isEmpty()) {
            return new Response(400, "Query parameter 'port' is required and must be an integer from 1 to 65535");
        }
        return callTcpServer(port.getAsInt());
    }

    private Response callTcpServer(int port) {
        var target = client.host() + ":" + port;
        try {
            return new Response(200, client.exchange(port, "Hello " + port + ", how are you?"));
        } catch (SocketTimeoutException e) {
            LOG.log(WARNING, "Timed out talking to " + target + ": " + e.getMessage());
            return new Response(504, "Gateway Timeout: no reply from " + target + " (" + describe(e) + ")");
        } catch (IOException e) {
            LOG.log(WARNING, "Cannot talk to " + target + ": " + describe(e));
            return new Response(502, "Bad Gateway: cannot talk to " + target + " (" + describe(e) + ")");
        }
    }

    /** Value of the first {@code port} query parameter, if it is a valid TCP port number. */
    private static OptionalInt parsePort(String rawQuery) {
        if (rawQuery == null) {
            return OptionalInt.empty();
        }
        try {
            for (var pair : rawQuery.split("&")) {
                int eq = pair.indexOf('=');
                var name = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), UTF_8);
                if (!name.equals("port")) {
                    continue;
                }
                var value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), UTF_8);
                if (!DIGITS.matcher(value).matches()) {
                    return OptionalInt.empty();
                }
                int port = Integer.parseInt(value);
                return port >= 1 && port <= 65535 ? OptionalInt.of(port) : OptionalInt.empty();
            }
        } catch (IllegalArgumentException _) {
            // malformed percent-encoding
        }
        return OptionalInt.empty();
    }

    private static void send(HttpExchange exchange, Response response) throws IOException {
        var body = response.body().getBytes(UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        if (body.length == 0) {
            exchange.sendResponseHeaders(response.status(), -1);
            return;
        }
        exchange.sendResponseHeaders(response.status(), body.length);
        exchange.getResponseBody().write(body);
    }

    private static String describe(IOException e) {
        var name = e.getClass().getSimpleName();
        return e.getMessage() == null ? name : name + ": " + e.getMessage();
    }

    /** Keeps client-controlled text from forging log lines. */
    private static String describeRequest(HttpExchange exchange) {
        var uri = exchange.getRequestURI();
        var text = exchange.getRequestMethod() + " " + uri.getRawPath()
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        var shown = text.length() > MAX_LOGGED_CHARS ? text.substring(0, MAX_LOGGED_CHARS) + "..." : text;
        return shown.replaceAll("\\p{Cntrl}", "?");
    }
}
