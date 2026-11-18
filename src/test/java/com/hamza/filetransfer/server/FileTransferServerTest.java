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
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Talks the raw wire protocol to a real server on a free local port. */
class FileTransferServerTest {

    @TempDir
    static Path tlsDir;
    private static ServerTls serverTls;

    @TempDir
    Path storage;

    private int port;
    private StorageCrypto crypto;
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
        crypto = StorageCrypto.loadOrCreate(storage.resolve("keys"));
        new FileTransferServer(port, storage.resolve("users"), accounts, serverTls.context(), crypto, session -> {}).startAsync();

        KnownServers known = new KnownServers(storage.resolve("known_servers.properties"));
        known.trust("127.0.0.1", port, serverTls.fingerprint());
        SSLContext context = SSLContext.getInstance(Tls.PROTOCOL);
        context.init(null, new TrustManager[]{new TofuTrustManager(known, "127.0.0.1", port)}, null);
        clientTls = context.getSocketFactory();
    }

    @Test
    void aBusyPortFailsAtStartInsteadOfSilently() throws Exception {
        AccountStore accounts = new AccountStore(storage.resolve("other-accounts.properties"));
        FileTransferServer second = new FileTransferServer(port, storage.resolve("other-users"), accounts, serverTls.context(), crypto, s -> {});
        assertThrows(java.net.BindException.class, second::startAsync, "the first server already holds this port");
    }

    @Test
    void storedFilesAndMessagesAreEncryptedOnDisk() throws Exception {
        Wire ali = login("Ali", "secret1");
        Wire bob = login("bob", "secret2");
        byte[] data = "TOP-SECRET-FILE-CONTENT ".repeat(5000).getBytes(StandardCharsets.UTF_8);

        bob.sendText("Ali", "secret-chat-text");
        bob.expectSendOk();
        Wire upload = transfer("bob", bob.token);
        upload.sendFile("Ali", "secret-report.pdf", data);
        upload.expectSendOk();

        List<Path> stored;
        try (var walk = Files.walk(storage.resolve("users"))) {
            stored = walk.filter(Files::isRegularFile).toList();
        }
        assertEquals(6, stored.size(), "the .bin, its inbox .meta, and an IN + OUT thread .meta for each message: " + stored);
        for (Path p : stored) {
            String raw = new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
            assertTrue(StorageCrypto.looksEncrypted(p), p + " is encrypted");
            for (String secret : List.of("TOP-SECRET", "secret-chat-text", "secret-report", "fromUser")) {
                assertFalse(raw.contains(secret), p + " leaks " + secret);
            }
        }

        // ...and still reads back: the thread lists the text, the download returns the exact bytes.
        assertArrayEquals(data, download(ali, "Ali", "secret-report.pdf"));
    }

    @Test
    void plainFilesFromOlderVersionsAreEncryptedAtStartAndStillWork() throws Exception {
        // What a 2.6.0 server left on disk: a plain .bin and plain .meta files.
        Path users = storage.resolve("old-users");
        String id = UUID.randomUUID().toString();
        byte[] data = "old plain file".getBytes(StandardCharsets.UTF_8);
        Path inbox = Files.createDirectories(users.resolve("Ali/inbox"));
        Files.write(inbox.resolve(id + ".bin"), data);
        Files.writeString(inbox.resolve(id + ".meta"), "messageId=" + id + "\nfromUser=bob\ntoUser=Ali\n"
                + "fileName=old.txt\nfileSize=" + data.length + "\ntimestamp=2026-11-17T08:00:00Z\ntimestampMillis=1\n");
        Files.writeString(inbox.resolve("left-over.meta.encrypting.tmp"), "from a crash during migration");

        int oldPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            oldPort = probe.getLocalPort();
        }
        AccountStore accounts = new AccountStore(storage.resolve("accounts.properties"));
        new FileTransferServer(oldPort, users, accounts, serverTls.context(), crypto, s -> {}).startAsync();

        assertTrue(StorageCrypto.looksEncrypted(inbox.resolve(id + ".bin")));
        assertTrue(StorageCrypto.looksEncrypted(inbox.resolve(id + ".meta")));
        assertFalse(Files.exists(inbox.resolve("left-over.meta.encrypting.tmp")), "temp file from a crash is removed");

        // Ali logs in to that server and downloads the migrated file.
        port = oldPort;
        KnownServers known = new KnownServers(storage.resolve("known_servers.properties"));
        known.trust("127.0.0.1", port, serverTls.fingerprint());
        SSLContext context = SSLContext.getInstance(Tls.PROTOCOL);
        context.init(null, new TrustManager[]{new TofuTrustManager(known, "127.0.0.1", port)}, null);
        clientTls = context.getSocketFactory();
        Wire ali = login("Ali", "secret1");
        Wire transfer = transfer("Ali", ali.token);
        transfer.out.writeUTF(Protocol.DOWNLOAD);
        transfer.out.writeUTF(id);
        transfer.out.flush();
        assertEquals(Protocol.FILE, transfer.readUTF());
        assertEquals("old.txt", transfer.readUTF());
        assertEquals(data.length, transfer.in.readLong());
        assertArrayEquals(data, transfer.in.readNBytes(data.length));
    }

    @Test
    void aStoredFileMovedToAnotherInboxDoesNotDecrypt() throws Exception {
        login("Ali", "secret1"); // creates the account
        Wire bob = login("bob", "secret2");
        Wire carol = login("carol", "secret3");
        Wire upload = transfer("bob", bob.token);
        upload.sendFile("Ali", "for-ali.txt", "only for Ali".getBytes(StandardCharsets.UTF_8));
        String aliFile = upload.expectSendOk();
        upload = transfer("bob", bob.token);
        upload.sendFile("carol", "for-carol.txt", "for Carol".getBytes(StandardCharsets.UTF_8));
        String carolFile = upload.expectSendOk();

        // Someone with disk access copies Ali's file over Carol's: Carol must not get Ali's content.
        Path users = storage.resolve("users");
        Files.copy(users.resolve("Ali/inbox/" + aliFile + ".bin"), users.resolve("carol/inbox/" + carolFile + ".bin"),
                StandardCopyOption.REPLACE_EXISTING);

        Wire transfer = transfer("carol", carol.token);
        transfer.out.writeUTF(Protocol.DOWNLOAD);
        transfer.out.writeUTF(carolFile);
        transfer.out.flush();
        assertEquals(Protocol.FILE, transfer.readUTF());
        transfer.readUTF();
        long size = transfer.in.readLong();
        assertThrows(IOException.class, () -> transfer.in.readFully(new byte[(int) size]),
                "the server stops instead of sending the moved file");
    }

    /** Waits for the INCOMING_FILE push for {@code fileName}, then downloads it on a TRANSFER socket. */
    private byte[] download(Wire receiver, String user, String fileName) throws Exception {
        String fileId = null;
        while (fileId == null) {
            String type = receiver.readUTF();
            if (Protocol.INCOMING_TEXT.equals(type)) {
                receiver.readUTF(); // id
                receiver.readUTF(); // from
                receiver.readUTF(); // timestamp
            } else if (Protocol.INCOMING_FILE.equals(type)) {
                String id = receiver.readUTF();
                receiver.readUTF(); // from
                String name = receiver.readUTF();
                receiver.in.readLong();
                receiver.readUTF(); // timestamp
                if (name.equals(fileName)) fileId = id;
            } else {
                fail("unexpected push " + type);
            }
        }
        Wire t = transfer(user, receiver.token);
        t.out.writeUTF(Protocol.DOWNLOAD);
        t.out.writeUTF(fileId);
        t.out.flush();
        assertEquals(Protocol.FILE, t.readUTF());
        assertEquals(fileName, t.readUTF());
        byte[] data = new byte[(int) t.in.readLong()];
        t.in.readFully(data);
        return data;
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
    void deleteForMeRemovesOnlyMyCopy() throws Exception {
        Wire ali = login("Ali", "secret1");
        Wire bob = login("bob", "secret2");
        bob.sendText("Ali", "hello ali");
        String id = bob.expectSendOk();
        assertEquals(Protocol.INCOMING_TEXT, ali.readUTF());
        ali.readUTF(); ali.readUTF(); ali.readUTF();

        ali.deleteMessage("bob", id, Protocol.DELETE_FOR_ME); // a received message: allowed for me
        ali.expectDeleteOk(id);
        assertEquals(List.of(), ali.listThread("bob"), "gone for Ali");
        assertEquals(List.of(id + " OUT TEXT hello ali"), bob.listThread("Ali"), "bob still has it");
        bob.socket.setSoTimeout(300);
        assertThrows(java.net.SocketTimeoutException.class, bob::readUTF, "bob is not told");
    }

    @Test
    void deleteForEveryoneLeavesPlaceholdersAndTellsThePeer() throws Exception {
        Wire ali = login("Ali", "secret1");
        Wire bob = login("bob", "secret2");
        bob.sendText("Ali", "oops, wrong chat");
        String id = bob.expectSendOk();
        assertEquals(Protocol.INCOMING_TEXT, ali.readUTF());
        ali.readUTF(); ali.readUTF(); ali.readUTF();

        bob.deleteMessage("Ali", id, Protocol.DELETE_FOR_EVERYONE);
        bob.expectDeleteOk(id);
        assertEquals(Protocol.MESSAGE_DELETED, ali.readUTF());
        assertEquals(id, ali.readUTF());
        assertEquals("bob", ali.readUTF());
        assertEquals(List.of(id + " OUT DELETED"), bob.listThread("Ali"));
        assertEquals(List.of(id + " IN DELETED"), ali.listThread("bob"));
        Path placeholder = storage.resolve("users/Ali/threads/bob/" + id + ".meta");
        assertTrue(StorageCrypto.looksEncrypted(placeholder), "the placeholder is encrypted like every .meta");
        String stored = new String(crypto.decrypt(Files.readAllBytes(placeholder), "ali/threads/bob/" + id + ".meta"), StandardCharsets.UTF_8);
        assertTrue(stored.contains("messageType=DELETED"), stored);
        assertFalse(stored.contains("wrong chat"), "the text is gone from the server");

        // the placeholder itself can be removed, for me only
        ali.deleteMessage("bob", id, Protocol.DELETE_FOR_ME);
        ali.expectDeleteOk(id);
        assertEquals(List.of(), ali.listThread("bob"));
    }

    @Test
    void aDeleteForEveryoneThatFailsPartWayCanBeRetried() throws Exception {
        Wire ali = login("Ali", "secret1");
        Wire bob = login("bob", "secret2");
        bob.sendText("Ali", "sent by mistake");
        String id = bob.expectSendOk();
        assertEquals(Protocol.INCOMING_TEXT, ali.readUTF());
        ali.readUTF(); ali.readUTF(); ali.readUTF();

        // Ali's copy can't be read for now (damaged): the delete stops on her side...
        Path aliCopy = storage.resolve("users/Ali/threads/bob/" + id + ".meta");
        byte[] good = Files.readAllBytes(aliCopy);
        Files.write(aliCopy, "not an encrypted file".getBytes(StandardCharsets.UTF_8));
        bob.deleteMessage("Ali", id, Protocol.DELETE_FOR_EVERYONE);
        assertEquals(ErrorCode.DELETE_INCOMPLETE, bob.expectError());
        // ...and bob's copy is untouched, so "Delete for everyone" is still there to retry
        assertEquals(List.of(id + " OUT TEXT sent by mistake"), bob.listThread("Ali"));
        ali.socket.setSoTimeout(300);
        assertThrows(java.net.SocketTimeoutException.class, ali::readUTF, "Ali is not told yet");
        ali.socket.setSoTimeout(10_000);

        // once her copy is readable again, the retry finishes the job
        Files.write(aliCopy, good);
        bob.deleteMessage("Ali", id, Protocol.DELETE_FOR_EVERYONE);
        bob.expectDeleteOk(id);
        assertEquals(Protocol.MESSAGE_DELETED, ali.readUTF());
        assertEquals(id, ali.readUTF());
        assertEquals("bob", ali.readUTF());
        assertEquals(List.of(id + " OUT DELETED"), bob.listThread("Ali"));
        assertEquals(List.of(id + " IN DELETED"), ali.listThread("bob"));

        // deleting again is harmless: OK, and Ali isn't told twice
        bob.deleteMessage("Ali", id, Protocol.DELETE_FOR_EVERYONE);
        bob.expectDeleteOk(id);
        ali.socket.setSoTimeout(300);
        assertThrows(java.net.SocketTimeoutException.class, ali::readUTF);
    }

    @Test
    void onlyTheSenderCanDeleteForEveryone() throws Exception {
        Wire ali = login("Ali", "secret1");
        Wire bob = login("bob", "secret2");
        bob.sendText("Ali", "keep this");
        String id = bob.expectSendOk();
        assertEquals(Protocol.INCOMING_TEXT, ali.readUTF());
        ali.readUTF(); ali.readUTF(); ali.readUTF();

        ali.deleteMessage("bob", id, Protocol.DELETE_FOR_EVERYONE);
        assertEquals(ErrorCode.NOT_YOUR_MESSAGE, ali.expectError());
        assertEquals(List.of(id + " OUT TEXT keep this"), bob.listThread("Ali"));
        assertEquals(List.of(id + " IN TEXT keep this"), ali.listThread("bob"));
    }

    @Test
    void deletingAFileForEveryoneRemovesItsBytes() throws Exception {
        Wire ali = login("Ali", "secret1");
        Wire bob = login("bob", "secret2");
        Wire upload = transfer("bob", bob.token);
        upload.sendFile("Ali", "photo.jpg", new byte[5000]);
        String id = upload.expectSendOk();
        assertEquals(Protocol.INCOMING_FILE, ali.readUTF());
        for (int i = 0; i < 3; i++) ali.readUTF();
        ali.in.readLong();
        ali.readUTF();
        Path inbox = storage.resolve("users/Ali/inbox");
        assertTrue(Files.exists(inbox.resolve(id + ".bin")));

        bob.deleteMessage("Ali", id, Protocol.DELETE_FOR_EVERYONE);
        bob.expectDeleteOk(id);
        assertFalse(Files.exists(inbox.resolve(id + ".bin")), "bytes gone");
        assertFalse(Files.exists(inbox.resolve(id + ".meta")));
        Wire download = transfer("Ali", ali.token);
        download.out.writeUTF(Protocol.DOWNLOAD);
        download.out.writeUTF(id);
        download.out.flush();
        assertEquals(ErrorCode.FILE_NOT_FOUND, download.expectError());
    }

    @Test
    void badDeletesAreRejectedAndTheConnectionSurvives() throws Exception {
        Wire ali = login("Ali", "secret1");
        login("bob", "secret2");
        ali.deleteMessage("bob", "11111111-1111-1111-1111-111111111111", Protocol.DELETE_FOR_ME);
        assertEquals(ErrorCode.MESSAGE_NOT_FOUND, ali.expectError());
        ali.deleteMessage("bob", "..\\..\\accounts", Protocol.DELETE_FOR_ME); // never becomes a path
        assertEquals(ErrorCode.MESSAGE_NOT_FOUND, ali.expectError());
        ali.deleteMessage("bob", "11111111-1111-1111-1111-111111111111", "EVERYTHING");
        assertEquals(ErrorCode.BAD_DELETE_SCOPE, ali.expectError());
        ali.deleteMessage("nobody", "11111111-1111-1111-1111-111111111111", Protocol.DELETE_FOR_ME);
        assertEquals(ErrorCode.UNKNOWN_USER, ali.expectError());
        assertTrue(Files.exists(storage.resolve("accounts.properties")));
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
        byte[] plain = crypto.decrypt(Files.readAllBytes(meta), "bob/threads/ali/" + messageId + ".meta");
        Properties p = new Properties();
        p.load(new StringReader(new String(plain, StandardCharsets.UTF_8)));
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
        transfer.writeVersion(Protocol.VERSION);
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
    void anUploadCutOffHalfwayLeavesNothingBehind() throws Exception {
        // What a cancelled upload looks like to the server: the transfer socket closes mid-file.
        Wire ali = login("Ali", "secret1");
        Wire bob = login("bob", "secret2");
        Path inbox = storage.resolve("users/Ali/inbox");

        Wire upload = transfer("bob", bob.token);
        upload.out.writeUTF(Protocol.SEND_FILE);
        upload.out.writeUTF("Ali");
        upload.out.writeUTF("big.bin");
        upload.out.writeLong(1_000_000);
        upload.out.write(new byte[10_000]);
        upload.out.flush();
        assertTrue(waitUntil(() -> countFiles(inbox) == 1), "the server started writing the file");

        upload.socket.close();
        assertTrue(waitUntil(() -> countFiles(inbox) == 0), "the half-written file is deleted");
        assertFalse(Files.exists(storage.resolve("users/Ali/threads")), "no message for the recipient");
        assertFalse(Files.exists(storage.resolve("users/bob/threads")), "no message for the sender");
        ali.socket.setSoTimeout(300);
        assertThrows(java.net.SocketTimeoutException.class, ali::readUTF, "the recipient is not notified");
    }

    private static long countFiles(Path dir) {
        if (!Files.isDirectory(dir)) return 0;
        try (var files = Files.list(dir)) {
            return files.count();
        } catch (IOException e) {
            return -1;
        }
    }

    private static boolean waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(50);
        }
        return false;
    }

    @Test
    void uploadErrorsOnATransferSocketAreReportedThere() throws Exception {
        Wire bob = login("bob", "secret2");

        Wire upload = transfer("bob", bob.token);
        upload.sendFile("nobody", "a.txt", new byte[1000]);
        assertEquals(ErrorCode.UNKNOWN_RECIPIENT, upload.expectError());
    }

    @Test
    void aClientWithAnotherVersionIsRefusedBeforeLogin() throws Exception {
        Wire w = connect();
        w.writeVersion(Protocol.VERSION + 1);
        w.rawHello("newbie", "secret1");
        assertEquals(Protocol.AUTH_FAILED, w.readUTF());
        assertEquals(List.of(ErrorCode.VERSION_MISMATCH, String.valueOf(Protocol.VERSION), String.valueOf(Protocol.VERSION + 1)),
                w.readErrorWithArgs());

        // nothing was registered: the name is still free for another password
        assertEquals(Protocol.HELLO_OK, connect().hello("newbie", "other-password"));
    }

    @Test
    void aClientFromBeforeVersioningGetsAReadableVersionError() throws Exception {
        Wire w = connect();
        w.rawHello("old", "secret1"); // what a 2.0.0 pre-release client sends
        assertEquals(Protocol.AUTH_FAILED, w.readUTF());
        assertEquals(List.of(ErrorCode.VERSION_MISMATCH, String.valueOf(Protocol.VERSION), "0"), w.readErrorWithArgs());
    }

    @Test
    void transferSocketsCheckTheVersionToo() throws Exception {
        Wire ali = login("ali", "secret1");
        Wire transfer = connect();
        transfer.writeVersion(Protocol.VERSION + 1);
        transfer.out.writeUTF(Protocol.HELLO_TRANSFER);
        transfer.out.writeUTF("ali");
        transfer.out.writeUTF(ali.token);
        transfer.out.flush();
        assertEquals(Protocol.AUTH_FAILED, transfer.readUTF());
        assertEquals(ErrorCode.VERSION_MISMATCH, transfer.readErrorWithArgs().getFirst());
    }

    @Test
    void transferSocketsNeedTheSessionToken() throws Exception {
        login("Ali", "secret1");

        Wire transfer = connect();
        transfer.writeVersion(Protocol.VERSION);
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
        w.writeVersion(Protocol.VERSION);
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

        void writeVersion(int version) throws IOException {
            out.writeUTF(Protocol.CONNECT);
            out.writeInt(version);
        }

        String hello(String user, String password) throws IOException {
            writeVersion(Protocol.VERSION);
            rawHello(user, password);
            String reply = in.readUTF();
            if (Protocol.HELLO_OK.equals(reply)) {
                in.readUTF(); // canonical name
                token = in.readUTF();
            }
            return reply;
        }

        /** HELLO without the CONNECT version step before it. */
        void rawHello(String user, String password) throws IOException {
            out.writeUTF(Protocol.HELLO);
            out.writeUTF(user);
            out.writeUTF(password);
            out.flush();
        }

        /** Reads {@code <code> <argCount> <args...>} and returns code followed by the args. */
        List<String> readErrorWithArgs() throws IOException {
            List<String> all = new ArrayList<>();
            all.add(in.readUTF());
            int n = in.readInt();
            for (int i = 0; i < n; i++) all.add(in.readUTF());
            return all;
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

        void deleteMessage(String peer, String messageId, String scope) throws IOException {
            out.writeUTF(Protocol.DELETE_MESSAGE);
            out.writeUTF(peer);
            out.writeUTF(messageId);
            out.writeUTF(scope);
            out.flush();
        }

        void expectDeleteOk(String messageId) throws IOException {
            assertEquals(Protocol.DELETE_OK, in.readUTF());
            assertEquals(messageId, in.readUTF());
        }

        /** One line per message: "id DIRECTION TYPE detail" (detail: the text, the file name, or nothing). */
        List<String> listThread(String peer) throws IOException {
            out.writeUTF(Protocol.LIST_THREAD);
            out.writeUTF(peer);
            out.flush();
            assertEquals(Protocol.THREAD_MESSAGES, in.readUTF());
            assertEquals(peer, in.readUTF());
            int n = in.readInt();
            List<String> lines = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                String id = in.readUTF(), direction = in.readUTF(), type = in.readUTF();
                in.readUTF(); in.readUTF(); in.readUTF(); // from, to, timestamp
                String detail = switch (type) {
                    case "TEXT" -> in.readUTF();
                    case "FILE" -> { String name = in.readUTF(); in.readLong(); in.readBoolean(); yield name; }
                    default -> "";
                };
                lines.add((id + " " + direction + " " + type + " " + detail).strip());
            }
            return lines;
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
