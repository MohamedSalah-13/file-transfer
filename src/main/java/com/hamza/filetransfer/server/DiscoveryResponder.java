package com.hamza.filetransfer.server;

import com.hamza.filetransfer.shared.Discovery;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;

/**
 * Answers LAN discovery queries (see {@link Discovery}) with this server's TCP port and name.
 * Optional: if the UDP port can't be opened, the server still works and clients type the address.
 */
public final class DiscoveryResponder implements Closeable {

    private final DatagramSocket socket;
    private final byte[] reply;

    /**
     * Opens {@code udpPort} (normally {@link Discovery#PORT}) and starts answering on a daemon thread.
     * The port is shared, so several servers on one machine can all answer.
     */
    public DiscoveryResponder(int udpPort, int tcpPort, String name) throws IOException {
        this.reply = Discovery.reply(tcpPort, name);
        DatagramSocket s = new DatagramSocket(null);
        try {
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(udpPort));
        } catch (IOException e) {
            s.close();
            throw e;
        }
        this.socket = s;
        Thread t = new Thread(this::answerLoop, "discovery-responder");
        t.setDaemon(true);
        t.start();
    }

    public int localPort() {
        return socket.getLocalPort();
    }

    private void answerLoop() {
        byte[] buf = new byte[Discovery.QUERY_SIZE * 2];
        while (!socket.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);
                // Never answer with more bytes than we got (no amplification of spoofed queries).
                if (packet.getLength() >= reply.length && Discovery.isQuery(packet.getData(), packet.getLength())) {
                    socket.send(new DatagramPacket(reply, reply.length, packet.getSocketAddress()));
                }
            } catch (SocketException closed) {
                return;
            } catch (IOException e) {
                // one bad packet or a send failure: keep answering others
            }
        }
    }

    @Override
    public void close() {
        socket.close();
    }
}
