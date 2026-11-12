package com.example.tcp.api;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.util.OptionalInt;
import java.util.regex.Pattern;

import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Error;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.micronaut.http.HttpStatus.BAD_GATEWAY;
import static io.micronaut.http.HttpStatus.BAD_REQUEST;
import static io.micronaut.http.HttpStatus.GATEWAY_TIMEOUT;
import static io.micronaut.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static io.micronaut.http.HttpStatus.METHOD_NOT_ALLOWED;
import static io.micronaut.http.HttpStatus.NOT_FOUND;
import static io.micronaut.http.HttpStatus.OK;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Handles {@code GET /api/tcp?port=N}: sends {@code Hello N, how are you?} to the TCP server on port N and returns
 * the reply as plain text.
 *
 * <p>Failures are reported as HTTP status codes: 400 for a missing or invalid port, 404 for other paths, 405 for
 * other methods, 502 when the TCP server cannot be reached or does not answer properly, 504 when it takes too long.
 */
@Controller("/api/tcp")
@Produces(MediaType.TEXT_PLAIN)
@ExecuteOn(TaskExecutors.BLOCKING)
public final class TcpController {

    private static final Logger LOG = LoggerFactory.getLogger(TcpController.class);
    private static final Pattern DIGITS = Pattern.compile("[0-9]{1,5}");
    private static final int MAX_LOGGED_CHARS = 200;

    private final TcpLineClient client;

    public TcpController(TcpLineClient client) {
        this.client = client;
    }

    @Get(uris = {"", "/"}, headRoute = false)
    public HttpResponse<String> echo(HttpRequest<?> request) {
        long startedAt = System.nanoTime();
        HttpResponse<String> response;
        try {
            var port = parsePort(request.getUri().getRawQuery());
            response = port.isPresent()
                    ? callTcpServer(port.getAsInt())
                    : text(BAD_REQUEST, "Query parameter 'port' is required and must be an integer from 1 to 65535");
        } catch (RuntimeException e) {
            LOG.error("Unexpected error while handling a request", e);
            response = text(INTERNAL_SERVER_ERROR, "Internal Server Error");
        }
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;
        LOG.info("{} {} ({} ms)", response.code(), describeRequest(request), elapsedMillis);
        return response;
    }

    @Error(status = NOT_FOUND, global = true)
    public HttpResponse<String> notFound() {
        return text(NOT_FOUND, "Not Found");
    }

    @Error(status = METHOD_NOT_ALLOWED, global = true)
    public HttpResponse<String> methodNotAllowed() {
        return text(METHOD_NOT_ALLOWED, "Method Not Allowed").header(HttpHeaders.ALLOW, "GET");
    }

    private HttpResponse<String> callTcpServer(int port) {
        var target = client.host() + ":" + port;
        try {
            return text(OK, client.exchange(port, "Hello " + port + ", how are you?"));
        } catch (SocketTimeoutException e) {
            LOG.warn("Timed out talking to {}: {}", target, e.getMessage());
            return text(GATEWAY_TIMEOUT, "Gateway Timeout: no reply from " + target + " (" + describe(e) + ")");
        } catch (IOException e) {
            LOG.warn("Cannot talk to {}: {}", target, describe(e));
            return text(BAD_GATEWAY, "Bad Gateway: cannot talk to " + target + " (" + describe(e) + ")");
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

    private static MutableHttpResponse<String> text(HttpStatus status, String body) {
        return HttpResponse.<String>status(status).body(body)
                .header(HttpHeaders.CONTENT_TYPE, "text/plain; charset=utf-8");
    }

    private static String describe(IOException e) {
        var name = e.getClass().getSimpleName();
        return e.getMessage() == null ? name : name + ": " + e.getMessage();
    }

    /** Keeps client-controlled text from forging log lines. */
    private static String describeRequest(HttpRequest<?> request) {
        var uri = request.getUri();
        var text = request.getMethodName() + " " + uri.getRawPath()
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        var shown = text.length() > MAX_LOGGED_CHARS ? text.substring(0, MAX_LOGGED_CHARS) + "..." : text;
        return shown.replaceAll("\\p{Cntrl}", "?");
    }
}
