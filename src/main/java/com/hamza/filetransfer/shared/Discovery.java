package com.hamza.filetransfer.shared;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;

/**
 * LAN discovery over UDP: a client broadcasts a query to {@link #PORT}, and every server on the
 * network answers with its TCP port and name. The client then connects over TLS as usual, so an
 * answer is only a hint: a fake server can't pass the certificate check.
 *
 * <p>Datagrams (DataOutputStream encoding):
 * <ul>
 *   <li>query: {@code "FT_DISCOVER" <protocolVersion:int>}, zero-padded to {@link #QUERY_SIZE} bytes;</li>
 *   <li>reply: {@code "FT_SERVER" <protocolVersion:int> <tcpPort:int> <name:UTF>}.</li>
 * </ul>
 * A server never sends a reply larger than the query it got, so a spoofed query can't be used to
 * flood someone else with bigger answers.
 */
public final class Discovery {

    /** UDP port servers listen on. Fixed, because the client doesn't know the server's TCP port yet. */
    public static final int PORT = 5001;
    /** Queries are padded to this size; replies must fit in it. */
    public static final int QUERY_SIZE = 256;
    public static final int MAX_NAME_CHARS = 64;

    private static final String QUERY = "FT_DISCOVER";
    private static final String REPLY = "FT_SERVER";

    /** A server that answered. {@code host} is the address the answer came from. */
    public record Found(String host, int port, String name, int version) {
        public boolean sameVersion() {
            return version == Protocol.VERSION;
        }
    }

    private Discovery() {}

    public static byte[] query() {
        byte[] padded = new byte[QUERY_SIZE];
        byte[] body = encode(out -> {
            out.writeUTF(QUERY);
            out.writeInt(Protocol.VERSION);
        });
        System.arraycopy(body, 0, padded, 0, body.length);
        return padded;
    }

    public static boolean isQuery(byte[] data, int length) {
        try {
            return QUERY.equals(in(data, length).readUTF());
        } catch (IOException e) {
            return false;
        }
    }

    public static byte[] reply(int tcpPort, String name) {
        String shortName = name.length() > MAX_NAME_CHARS ? name.substring(0, MAX_NAME_CHARS) : name;
        return encode(out -> {
            out.writeUTF(REPLY);
            out.writeInt(Protocol.VERSION);
            out.writeInt(tcpPort);
            out.writeUTF(shortName);
        });
    }

    /** The server described by a reply datagram, or null if it isn't a valid reply. */
    public static Found parseReply(byte[] data, int length, InetAddress from) {
        try {
            DataInputStream in = in(data, length);
            if (!REPLY.equals(in.readUTF())) return null;
            int version = in.readInt();
            int port = in.readInt();
            String name = in.readUTF();
            if (port < 1 || port > 65535) return null;
            if (name.length() > MAX_NAME_CHARS) name = name.substring(0, MAX_NAME_CHARS);
            return new Found(from.getHostAddress(), port, name.strip(), version);
        } catch (IOException e) {
            return null;
        }
    }

    private interface Writer {
        void write(DataOutputStream out) throws IOException;
    }

    private static byte[] encode(Writer w) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            w.write(out);
        } catch (IOException e) {
            throw new IllegalStateException(e); // in-memory stream
        }
        return bytes.toByteArray();
    }

    private static DataInputStream in(byte[] data, int length) {
        return new DataInputStream(new ByteArrayInputStream(data, 0, length));
    }
}
