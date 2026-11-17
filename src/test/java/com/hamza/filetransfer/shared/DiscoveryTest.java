package com.hamza.filetransfer.shared;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class DiscoveryTest {

    private static final InetAddress FROM = InetAddress.getLoopbackAddress();

    @Test
    void aReplyRoundTrips() {
        byte[] r = Discovery.reply(5050, "office-pc");
        Discovery.Found f = Discovery.parseReply(r, r.length, FROM);
        assertEquals(new Discovery.Found("127.0.0.1", 5050, "office-pc", Protocol.VERSION), f);
        assertTrue(f.sameVersion());
    }

    @Test
    void queriesAreRecognizedAndPadded() {
        byte[] q = Discovery.query();
        assertEquals(Discovery.QUERY_SIZE, q.length);
        assertTrue(Discovery.isQuery(q, q.length));
        assertFalse(Discovery.isQuery(q, 3), "truncated");
        byte[] r = Discovery.reply(5000, "x");
        assertFalse(Discovery.isQuery(r, r.length), "a reply is not a query");
    }

    @Test
    void junkIsIgnored() {
        byte[] junk = "hello world, not a reply".getBytes(StandardCharsets.UTF_8);
        assertNull(Discovery.parseReply(junk, junk.length, FROM));
        assertFalse(Discovery.isQuery(junk, junk.length));
        byte[] q = Discovery.query();
        assertNull(Discovery.parseReply(q, q.length, FROM), "a query is not a reply");
        byte[] r = Discovery.reply(5000, "x");
        assertNull(Discovery.parseReply(r, r.length - 1, FROM), "truncated reply");
    }

    @Test
    void theLongestReplyFitsInAQuery() {
        // Arabic letters take 2 bytes each in writeUTF; the worst case is 3 bytes per char.
        String longest = "中".repeat(Discovery.MAX_NAME_CHARS + 10);
        byte[] r = Discovery.reply(65535, longest);
        assertTrue(r.length <= Discovery.QUERY_SIZE, "reply " + r.length + " bytes must not be larger than a query");
        assertEquals(Discovery.MAX_NAME_CHARS, Discovery.parseReply(r, r.length, FROM).name().length());
    }

    @Test
    void anotherProtocolVersionIsFlagged() {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        try (java.io.DataOutputStream out = new java.io.DataOutputStream(b)) {
            out.writeUTF("FT_SERVER");
            out.writeInt(Protocol.VERSION + 1);
            out.writeInt(5000);
            out.writeUTF("newer");
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        Discovery.Found f = Discovery.parseReply(b.toByteArray(), b.size(), FROM);
        assertFalse(f.sameVersion());
    }
}
