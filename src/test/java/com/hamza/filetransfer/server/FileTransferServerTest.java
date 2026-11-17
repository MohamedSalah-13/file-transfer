package com.hamza.filetransfer.server;

import com.hamza.filetransfer.client.KnownServers;
import com.hamza.filetransfer.client.TofuTrustManager;
import com.hamza.filetransfer.shared.ErrorCode;
import com.hamza.filetransfer.shared.Protocol;
import com.hamza.filetransfer.shared.Tls;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/** Talks the raw wire protocol to a real server on a free local port. */
class FileTransferServerTest {

    @TempDir
    static Path tlsDir;
    private static ServerTls serverTls;

    @TempDir
    Path storage;

    private int port;
    private SSLSocketFactory clientTls; // trusts exactly this server's certificate
    private final List<Wire> opened = new ArrayList<>();

    @BeforeAll
    static void createCertificate() throws Exception {
        serverTls = ServerTls.loadOrCreate(tlsDir); // runs keytool once for the whole class
    }

    @BeforeEach
    void startServer() throws Exception {
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        AccountStore accounts = new AccountStore(storage.resolve("accounts.properties"));
        new FileTransferServer(port, storage.resolve("users"), accounts, serverTls.context(), session -> {}).startAsync();

        KnownServers known = new KnownServers(storage.resolve("known_servers.properties"));
        known.trust("127.0.0.1", port, serverTls.fingerprint());
        SSLContext context = SSLContext.getInstance(Tls.PROTOCOL);
        context.init(null, new TrustManager[]{new TofuTrustManager(known, "127.0.0.1", port)}, null);
        clientTls = context.getSocketFactory();
    }

    @Test
    void theCertificateIsKeptAcrossRestarts() throws Exception {
        assertEquals(serverTls.fingerprint(), ServerTls.loadOrCreate(tlsDir).fingerprint());
    }

    @Test
    void aPasswordFileWithoutAKeystoreStartsOver(@TempDir Path dir) throws Exception {
        // what a crash between saving the password and creating the certificate leaves behind
        Files.writeString(dir.resolve("server.p12.password"), "left-over");
        ServerTls created = ServerTls.loadOrCreate(dir);
        assertTrue(Files.exists(dir.resolve("server.p12")));
        assertEquals(created.fingerprint(), ServerTls.loadOrCreate(dir).fingerprint());
    }

    @Test
    void plainTcpClientsWithoutTlsAreRefused() throws Exception {
        Socket plain = connectRaw();
        plain.setSoTimeout(10_000);
        DataOutputStream out = new DataOutputStream(plain.getOutputStream());
        out.writeUTF(Protocol.HELLO);
        out.writeUTF("Ali");
        out.writeUTF("secret1");
        out.flush();

        // The server treats the bytes as a broken TLS handshake and closes the socket: no HELLO_OK in clear text.
        DataInputStream in = new DataInputStream(plain.getInputStream());
        assertThrows(IOException.class, in::readUTF);
        plain.close();
    }

    @AfterEach
    void closeClients() {
        for (Wire w : opened) w.closeQuietly();
    }

    @Test
    void sendingToYourselfIsRejectedAndTheConnectionSurvives() throws Exception {
        Wire ali = login("Ali", "secret1");

        ali.sendText("Ali", "note to self");
        assertEquals(ErrorCode.SELF_RECIPIENT, ali.expectError());

        ali.sendFile("ali", "a.txt", "data".getBytes()); // other spelling, same account
        assertEquals(ErrorCode.SELF_RECIPIENT, ali.expectError());

        assertTrue(ali.listUsers().contains("Ali"), "connection still usable");
    }

    @Test
    void aNewLoginReplacesTheOldSession() throws Exception {
        Wire first = login("bob", "secret2");
        Wire second = login("bob", "secret2"); // used to be HELLO_TAKEN

        assertThrows(IOException.class, first::readUTF, "old session is closed by the server");
        assertTrue(second.listUsers().contains("bob"));
    }

    @Test
    void aWrongPasswordDoesNotReplaceTheSession() throws Exception {
        Wire bob = login("bob", "secret2");

        Wire attacker = connect();
        assertEquals(Protocol.AUTH_FAILED, attacker.hello("bob", "not-the-password"));
        assertEquals(ErrorCode.WRONG_CREDENTIALS, attacker.readErrorBody());

        assertTrue(bob.listUsers().contains("bob"), "real session still online");
    }

    @Test
    void peerNamesAreResolvedToTheCanonicalAccountName() throws Exception {
        Wire ali = login("Ali", "secret1");
        Wire bob = login("bob", "secret2");

        bob.sendText("ALI", "hello");
        String messageId = bob.expectSendOk();

        assertEquals(Protocol.INCOMING_TEXT, ali.readUTF(), "push reaches Ali although bob typed ALI");
        assertEquals(messageId, ali.readUTF());
        assertEquals("bob", ali.readUTF());
        ali.readUTF(); // timestamp

        Path meta = storage.resolve("users/bob/threads/Ali/" + messageId + ".meta");
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(meta, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        assertEquals("Ali", p.getProperty("toUser"));
    }

    @Test
    void aDeadRecipientDoesNotBreakTheSender() throws Exception {
        Wire ali = login("Ali", "secret1");
        Wire bob = login("bob", "secret2");

        ali.socket.setSoLinger(true, 0); // reset instead of a clean close: the server still thinks Ali is online
        ali.socket.close();

        for (int i = 0; i < 3; i++) {
            bob.sendText("Ali", "message " + i);
            bob.expectSendOk();
        }
        bob.sendFile("Ali", "a.txt", new byte[100_000]);
        bob.expectSendOk();
        assertTrue(bob.listUsers().contains("bob"), "bob still connected");
    }

    @Test
    void anUploadedFileCanBeDownloadedOnATransferSocket() throws Exception {
        Wire ali = login("Ali", "secret1");
        Wire bob = login("bob", "secret2");
        byte[] data = new byte[300_000];
        for (int i = 0; i < data.length; i++) data[i] = (byte) i;

        bob.sendFile("Ali", "../../report.pdf", data); // on CONTROL, as 1.0.0 clients do: still accepted
        bob.expectSendOk();

        assertEquals(Protocol.INCOMING_FILE, ali.readUTF());
        String fileId = ali.readUTF();
        assertEquals("bob", ali.readUTF());
        assertEquals("report.pdf", ali.readUTF(), "name sanitized on the server");
        assertEquals(data.length, ali.in.readLong());
        ali.readUTF();

        Wire transfer = connect();
        transfer.out.writeUTF(Protocol.HELLO_TRANSFER);
        transfer.out.writeUTF("ali");
        transfer.out.writeUTF(ali.token);
        transfer.out.flush();
        assertEquals(Protocol.HELLO_OK, transfer.readUTF());

        transfer.out.writeUTF(Protocol.DOWNLOAD);
        transfer.out.writeUTF(fileId);
        transfer.out.flush();
        assertEquals(Protocol.FILE, transfer.readUTF());
        assertEquals("report.pdf", transfer.readUTF());
        assertEquals(data.length, transfer.in.readLong());
        assertArrayEquals(data, transfer.in.readNBytes(data.length));
    }

    @Test
    void uploadsOnATransferSocketAreAnsweredThereAndNotifyTheRecipient() throws Exception {
        Wire ali = login("Ali", "secret1");
        Wire bob = login("bob", "secret2");

        Wire upload = transfer("bob", bob.token);
        upload.sendFile("Ali", "photo.jpg", new byte[70_000]);
        String messageId = upload.expectSendOk(); // the reply comes on the transfer socket

        assertEquals(Protocol.INCOMING_FILE, ali.readUTF());
        assertEquals(messageId, ali.readUTF());

        // bob's CONTROL socket got nothing from the upload and still works.
        bob.socket.setSoTimeout(300);
        assertThrows(java.net.SocketTimeoutException.class, bob::readUTF);
        bob.socket.setSoTimeout(10_000);
        assertTrue(bob.listUsers().contains("bob"));
    }

    @Test
    void uploadErrorsOnATransferSocketAreReportedThere() throws Exception {
        Wire bob = login("bob", "secret2");

        Wire upload = transfer("bob", bob.token);
        upload.sendFile("nobody", "a.txt", new byte[1000]);
        assertEquals(ErrorCode.UNKNOWN_RECIPIENT, upload.expectError());
    }

    @Test
    void transferSocketsNeedTheSessionToken() throws Exception {
        login("Ali", "secret1");

        Wire transfer = connect();
        transfer.out.writeUTF(Protocol.HELLO_TRANSFER);
        transfer.out.writeUTF("Ali");
        transfer.out.writeUTF("guessed-token");
        transfer.out.flush();

        assertEquals(Protocol.AUTH_FAILED, transfer.readUTF());
        assertEquals(ErrorCode.INVALID_SESSION, transfer.readErrorBody());
    }

    @Test
    void unknownRecipientsAreRejectedWithoutDesyncingTheStream() throws Exception {
        Wire bob = login("bob", "secret2");

        bob.sendFile("nobody", "a.txt", new byte[50_000]);
        assertEquals(ErrorCode.UNKNOWN_RECIPIENT, bob.expectError());
        assertTrue(bob.listUsers().contains("bob"), "body was skipped, next command parses");
    }

    // ===== helpers =====

    /** A plain TCP connection, retried until the server thread is listening. */
    private Socket connectRaw() throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (true) {
            try {
                return new Socket("127.0.0.1", port);
            } catch (java.net.ConnectException e) {
                if (System.currentTimeMillis() > deadline) throw e;
                Thread.sleep(50);
            }
        }
    }

    /** A TLS connection that trusts this server's certificate, like a real client after trusting it. */
    private Wire connect() throws Exception {
        SSLSocket s = (SSLSocket) clientTls.createSocket(connectRaw(), "127.0.0.1", port, true);
        s.startHandshake();
        Wire w = new Wire(s);
        opened.add(w);
        return w;
    }

    private Wire transfer(String user, String token) throws Exception {
        Wire w = connect();
        w.out.writeUTF(Protocol.HELLO_TRANSFER);
        w.out.writeUTF(user);
        w.out.writeUTF(token);
        w.out.flush();
        assertEquals(Protocol.HELLO_OK, w.readUTF());
        return w;
    }

    private Wire login(String user, String password) throws Exception {
        Wire w = connect();
        assertEquals(Protocol.HELLO_OK, w.hello(user, password));
        return w;
    }

    private static final class Wire {
        final Socket socket;
        final DataInputStream in;
        final DataOutputStream out;
        String token;

        Wire(Socket socket) throws IOException {
            this.socket = socket;
            socket.setSoTimeout(10_000);
            in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        }

        String readUTF() throws IOException {
            return in.readUTF();
        }

        String hello(String user, String password) throws IOException {
            out.writeUTF(Protocol.HELLO);
            out.writeUTF(user);
            out.writeUTF(password);
            out.flush();
            String reply = in.readUTF();
            if (Protocol.HELLO_OK.equals(reply)) {
                in.readUTF(); // canonical name
                token = in.readUTF();
            }
            return reply;
        }

        /** Reads {@code <code> <argCount> <args...>} and returns the code. */
        String readErrorBody() throws IOException {
            String code = in.readUTF();
            int n = in.readInt();
            for (int i = 0; i < n; i++) in.readUTF();
            return code;
        }

        String expectError() throws IOException {
            assertEquals(Protocol.ERROR, in.readUTF());
            return readErrorBody();
        }

        String expectSendOk() throws IOException {
            assertEquals(Protocol.SEND_OK, in.readUTF());
            return in.readUTF();
        }

        void sendText(String to, String text) throws IOException {
            out.writeUTF(Protocol.SEND_TEXT);
            out.writeUTF(to);
            out.writeUTF(text);
            out.flush();
        }

        void sendFile(String to, String name, byte[] data) throws IOException {
            out.writeUTF(Protocol.SEND_FILE);
            out.writeUTF(to);
            out.writeUTF(name);
            out.writeLong(data.length);
            out.write(data);
            out.flush();
        }

        List<String> listUsers() throws IOException {
            out.writeUTF(Protocol.LIST_USERS);
            out.flush();
            assertEquals(Protocol.USERS, in.readUTF());
            int n = in.readInt();
            List<String> users = new ArrayList<>();
            for (int i = 0; i < n; i++) users.add(in.readUTF());
            return users;
        }

        void closeQuietly() {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
