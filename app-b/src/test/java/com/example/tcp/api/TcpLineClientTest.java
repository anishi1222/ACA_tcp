package com.example.tcp.api;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TcpLineClientTest {

    @Test
    void readsOneLineWithoutItsTerminator() throws IOException {
        assertEquals("reply", TcpLineClient.readLine(stream("reply\r\nnext\n")));
    }

    @Test
    void acceptsAnUnterminatedLastLine() throws IOException {
        assertEquals("partial", TcpLineClient.readLine(stream("partial")));
    }

    @Test
    void failsWhenTheConnectionClosesWithoutAReply() {
        assertThrows(IOException.class, () -> TcpLineClient.readLine(stream("")));
    }

    @Test
    void failsOnAnOverlongReplyLine() {
        var tooLong = "x".repeat(TcpLineClient.MAX_REPLY_BYTES + 1);

        assertThrows(IOException.class, () -> TcpLineClient.readLine(stream(tooLong)));
    }

    private static InputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(UTF_8));
    }
}
