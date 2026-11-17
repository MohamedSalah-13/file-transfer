package com.hamza.filetransfer.server;

import com.hamza.filetransfer.client.ServerFinder;
import com.hamza.filetransfer.shared.Discovery;
import com.hamza.filetransfer.shared.Protocol;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** A real responder on a free UDP port (not 5001, so a running server doesn't interfere). */
class DiscoveryResponderTest {

    private DiscoveryResponder responder;

    @BeforeEach
    void start() throws Exception {
        int udpPort;
        try (DatagramSocket probe = new DatagramSocket(0)) {
            udpPort = probe.getLocalPort();
        }
        responder = new DiscoveryResponder(udpPort, 5050, "test-server");
    }

    @AfterEach
    void stop() {
        responder.close();
    }

    @Test
    void theFinderSeesTheServerWithItsTcpPortAndName() throws Exception {
        List<Discovery.Found> found = ServerFinder.find(responder.localPort(), Duration.ofMillis(800));
        Discovery.Found f = found.stream().filter(x -> x.name().equals("test-server")).findFirst().orElseThrow(
                () -> new AssertionError("not found in " + found));
        assertEquals(5050, f.port());
        assertEquals(Protocol.VERSION, f.version());
        assertEquals(1, found.stream().filter(x -> x.name().equals("test-server")).count(),
                "answers from several networks are merged into one row");
        assertEquals("127.0.0.1", f.host(), "a server on this computer");
    }

    @Test
    void junkAndShortQueriesGetNoAnswer() throws Exception {
        assertNull(ask("not a discovery query, just some bytes".getBytes()));
        byte[] shortQuery = java.util.Arrays.copyOf(Discovery.query(), 16); // valid start, but smaller than the reply
        assertNull(ask(shortQuery), "the server never answers with more bytes than it got");
        assertNotNull(ask(Discovery.query()), "a real query still works afterwards");
    }

    private Discovery.Found ask(byte[] query) throws Exception {
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(400);
            s.send(new DatagramPacket(query, query.length, InetAddress.getLoopbackAddress(), responder.localPort()));
            byte[] buf = new byte[512];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try {
                s.receive(p);
            } catch (SocketTimeoutException e) {
                return null;
            }
            return Discovery.parseReply(p.getData(), p.getLength(), p.getAddress());
        }
    }
}
