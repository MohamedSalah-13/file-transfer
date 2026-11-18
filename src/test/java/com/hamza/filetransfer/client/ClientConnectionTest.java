package com.hamza.filetransfer.client;

import com.hamza.filetransfer.server.ServerTls;
import com.hamza.filetransfer.shared.ErrorCode;
import com.hamza.filetransfer.shared.Protocol;
import javafx.application.Platform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs {@link ClientConnection} against a scripted fake server, so timing-sensitive cases
 * (a stalled upload, a download cut off midway) are deterministic.
 * Callbacks go through {@code Platform.runLater}, so the JavaFX toolkit is started (needs a desktop).
 */
class ClientConnectionTest {

    @TempDir
    static Path tlsDir;
    private static ServerTls serverTls;

    @TempDir
    Path dir;

    private FakeServer server;
    private KnownServers known; // trusts the fake server's certificate, as after the first-use prompt
    private ClientConnection conn;

    @BeforeAll
    static void startFx() throws Exception {
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException alreadyStarted) {
            // another test class started it
        }
        Platform.setImplicitExit(false);
        serverTls = ServerTls.loadOrCreate(tlsDir);
    }

    @BeforeEach
    void startServer() throws IOException {
        server = new FakeServer(serverTls);
        known = new KnownServers(dir.resolve("known_servers.properties"));
        known.trust("127.0.0.1", server.port(), serverTls.fingerprint());
    }

    @Test
    void anUnknownServerMustBeTrustedFirstAndGetsNoPassword() throws Exception {
        KnownServers empty = new KnownServers(dir.resolve("empty.properties"));
        Future<ClientConnection> pending = connectAsync("carol", empty);
        Peer control = server.next();

        assertThrows(IOException.class, control::readHello, "handshake aborted before HELLO");
        ExecutionException e = assertThrows(ExecutionException.class, () -> pending.get(5, TimeUnit.SECONDS));
        AppError err = AppError.from(e.getCause());
        assertEquals(AppError.UNTRUSTED_SERVER, err.code());
        assertEquals(serverTls.fingerprint(), err.args().getFirst());

        // What the UI does after the user accepts the fingerprint:
        empty.trust("127.0.0.1", server.port(), err.args().getFirst());
        conn = login("carol", "carol", empty);
        assertEquals("carol", conn.getUsername());
    }

    @Test
    void aChangedCertificateIsBlocked() throws Exception {
        known.trust("127.0.0.1", server.port(), "AA:BB:CC");
        Future<ClientConnection> pending = connectAsync("carol", known);
        Peer control = server.next();

        assertThrows(IOException.class, control::readHello, "no password sent to an impostor");
        ExecutionException e = assertThrows(ExecutionException.class, () -> pending.get(5, TimeUnit.SECONDS));
        AppError err = AppError.from(e.getCause());
        assertEquals(AppError.SERVER_CERT_CHANGED, err.code());
        assertEquals(List.of(serverTls.fingerprint(), "AA:BB:CC", known.location().toString()), err.args());
    }

    @Test
    void aServerWithoutTlsFailsWithAClearError() throws Exception {
        try (ServerSocket plain = new ServerSocket(0)) {
            known.trust("127.0.0.1", plain.getLocalPort(), serverTls.fingerprint());
            CompletableFuture<ClientConnection> pending = CompletableFuture.supplyAsync(() -> {
                try {
                    return new ClientConnection("127.0.0.1", plain.getLocalPort(), "carol", "pw".toCharArray(), known);
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            });
            try (Socket s = plain.accept()) { // like a 1.x server answering in clear text
                DataOutputStream out = new DataOutputStream(s.getOutputStream());
                out.writeUTF(Protocol.ERROR);
                out.flush();
                ExecutionException e = assertThrows(ExecutionException.class, () -> pending.get(15, TimeUnit.SECONDS));
                assertEquals(AppError.TLS_FAILED, AppError.from(e.getCause()).code());
            }
        }
    }

    @AfterEach
    void stop() throws IOException {
        if (conn != null) conn.close();
        server.close();
    }

    @Test
    void loginReturnsTheCanonicalNameFromTheServer() throws Exception {
        conn = login("carol", "Carol");
        assertEquals("Carol", conn.getUsername());
    }

    @Test
    void aServerFromBeforeVersioningIsReportedAsAVersionMismatch() throws Exception {
        Future<ClientConnection> pending = connectAsync("carol");
        Peer control = server.next();
        control.readHello();
        control.out.writeUTF(Protocol.ERROR); // a 2.0.0 pre-release server does not know CONNECT
        control.out.writeUTF(ErrorCode.BAD_HANDSHAKE);
        control.out.writeInt(0);
        control.out.flush();

        ExecutionException e = assertThrows(ExecutionException.class, () -> pending.get(5, TimeUnit.SECONDS));
        AppError err = AppError.from(e.getCause());
        assertEquals(new AppError(ErrorCode.VERSION_MISMATCH, "0", String.valueOf(Protocol.VERSION)), err);
        assertFalse(I18n.error(err).startsWith(ErrorCode.VERSION_MISMATCH), "translated: " + I18n.error(err));
    }

    @Test
    void loginErrorsArriveAsTranslatableCodes() throws Exception {
        Future<ClientConnection> pending = connectAsync("carol");
        Peer control = server.next();
        control.readHello();
        control.out.writeUTF(Protocol.AUTH_FAILED);
        control.out.writeUTF(ErrorCode.WRONG_CREDENTIALS);
        control.out.writeInt(0);
        control.out.flush();

        ExecutionException e = assertThrows(ExecutionException.class, () -> pending.get(5, TimeUnit.SECONDS));
        assertEquals(ErrorCode.WRONG_CREDENTIALS, AppError.from(e.getCause()).code());
    }

    @Test
    void uploadsUseTheirOwnTransferSocketSoChatKeepsWorking() throws Exception {
        conn = login("carol", "carol");
        Peer control = server.lastControl;
        conn.start();

        // The fake server reads the upload header and then stops reading, so the upload stalls
        // once the socket buffers are full.
        Path big = dir.resolve("big.bin");
        Files.write(big, new byte[64 * 1024 * 1024]);
        CompletableFuture<Void> upload = uploadAsync("dave", big);
        Peer transfer = server.next();
        transfer.acceptTransferLogin();
        assertEquals(Protocol.SEND_FILE, transfer.in.readUTF());
        assertEquals("dave", transfer.in.readUTF());
        assertEquals("big.bin", transfer.in.readUTF());
        assertEquals(64L * 1024 * 1024, transfer.in.readLong());
        Thread.sleep(300);
        assertFalse(upload.isDone(), "upload is stalled");

        // The CONTROL socket is not involved, so chat requests still reach the server right away.
        long start = System.nanoTime();
        conn.requestThread("dave");
        assertEquals(Protocol.LIST_THREAD, control.in.readUTF());
        assertEquals("dave", control.in.readUTF());
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(millis < 1000, "LIST_THREAD arrived after " + millis + " ms while the upload was stalled");
        assertFalse(upload.isDone(), "upload still stalled");
    }

    @Test
    @Timeout(30)
    void aStalledUploadCanBeCancelled() throws Exception {
        conn = login("carol", "carol");
        Path big = dir.resolve("big.bin");
        Files.write(big, new byte[64 * 1024 * 1024]);
        TransferHandle handle = new TransferHandle();
        CompletableFuture<Void> upload = CompletableFuture.runAsync(() -> {
            try {
                conn.sendFile("dave", big, (d, t) -> {}, handle);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });
        Peer transfer = server.next();
        transfer.acceptTransferLogin();
        transfer.in.readUTF(); // SEND_FILE, then the fake server stops reading: the upload stalls
        Thread.sleep(300);
        assertFalse(upload.isDone(), "upload is stalled");

        long start = System.nanoTime();
        handle.cancel(); // from the FX thread in the app: must not wait for the stalled write
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(millis < 1000, "cancel() took " + millis + " ms");
        ExecutionException e = assertThrows(ExecutionException.class, () -> upload.get(5, TimeUnit.SECONDS));
        assertInstanceOf(TransferHandle.TransferCancelledException.class, e.getCause(), "cancelled, not an error");
        transfer.socket.setSoTimeout(5000);
        assertThrows(IOException.class, () -> {
            while (transfer.in.read(new byte[64 * 1024]) != -1) { } // the server side sees the socket close
            throw new EOFException();
        });
    }

    @Test
    @Timeout(30)
    void aDownloadCanBeCancelledAndLeavesNoPartialFile() throws Exception {
        conn = login("carol", "carol");
        Path downloads = Files.createDirectory(dir.resolve("downloads"));
        BlockingQueue<String> statuses = new LinkedBlockingQueue<>();
        TransferHandle handle = new TransferHandle();

        conn.startParallelDownload("id-1", downloads, (d, t) -> {}, statuses::add,
                err -> statuses.add("FAILED " + err), saved -> statuses.add("SAVED"), handle);

        Peer transfer = server.next();
        transfer.acceptTransferHello();
        transfer.out.writeUTF(Protocol.FILE);
        transfer.out.writeUTF("report.pdf");
        transfer.out.writeLong(1000);
        transfer.out.write(new byte[10]); // then the server goes quiet: the download waits
        transfer.out.flush();
        assertEquals("CONNECTING", statuses.poll(5, TimeUnit.SECONDS));
        assertEquals("DOWNLOADING", statuses.poll(5, TimeUnit.SECONDS));

        handle.cancel();
        assertEquals("CANCELLED", statuses.poll(5, TimeUnit.SECONDS), "cancelled, not failed");
        assertNull(statuses.poll(300, TimeUnit.MILLISECONDS), "nothing after CANCELLED");
        try (var files = Files.list(downloads)) {
            assertEquals(List.of(), files.toList(), "the partly written report.pdf is deleted");
        }
    }

    @Test
    @Timeout(30)
    void cancellingBeforeTheTransferStartsStopsItAtOnce() throws Exception {
        conn = login("carol", "carol");
        Path file = dir.resolve("notes.txt");
        Files.writeString(file, "x");
        TransferHandle handle = new TransferHandle();
        handle.cancel();
        assertThrows(TransferHandle.TransferCancelledException.class, () -> conn.sendFile("dave", file, (d, t) -> {}, handle));
    }

    @Test
    void anUploadSendsExactlyTheFileAndWaitsForSendOk() throws Exception {
        conn = login("carol", "carol");
        Path file = dir.resolve("notes.txt");
        Files.writeString(file, "hello dave");
        CompletableFuture<Void> upload = uploadAsync("dave", file);

        Peer transfer = server.next();
        transfer.acceptTransferLogin();
        assertEquals(Protocol.SEND_FILE, transfer.in.readUTF());
        assertEquals("dave", transfer.in.readUTF());
        assertEquals("notes.txt", transfer.in.readUTF());
        assertEquals(10, transfer.in.readLong());
        assertEquals("hello dave", new String(transfer.in.readNBytes(10)));
        Thread.sleep(100);
        assertFalse(upload.isDone(), "upload waits for the server's reply");

        transfer.out.writeUTF(Protocol.SEND_OK);
        transfer.out.writeUTF("11111111-1111-1111-1111-111111111111");
        transfer.out.flush();
        upload.get(5, TimeUnit.SECONDS);
        assertEquals(-1, transfer.in.read(), "client closes the transfer socket afterwards");
    }

    @Test
    void uploadErrorsFromTheServerFailThatUpload() throws Exception {
        conn = login("carol", "carol");
        Path file = dir.resolve("notes.txt");
        Files.writeString(file, "x");
        CompletableFuture<Void> upload = uploadAsync("nobody", file);

        Peer transfer = server.next();
        transfer.acceptTransferLogin();
        transfer.in.readUTF(); // SEND_FILE
        transfer.in.readUTF(); // to
        transfer.in.readUTF(); // name
        transfer.in.readNBytes((int) transfer.in.readLong());
        transfer.out.writeUTF(Protocol.ERROR);
        transfer.out.writeUTF(ErrorCode.UNKNOWN_RECIPIENT);
        transfer.out.writeInt(0);
        transfer.out.flush();

        ExecutionException e = assertThrows(ExecutionException.class, () -> upload.get(5, TimeUnit.SECONDS));
        assertEquals(ErrorCode.UNKNOWN_RECIPIENT, AppError.from(e.getCause()).code());
    }

    @Test
    void threadRepliesSayWhichPeerTheyBelongTo() throws Exception {
        conn = login("carol", "carol");
        Peer control = server.lastControl;
        BlockingQueue<String> replies = new LinkedBlockingQueue<>();
        conn.setOnThreadMessages((peer, msgs) -> replies.add(peer + ":" + msgs.size()));
        conn.start();

        conn.requestThread("dave");
        assertEquals(Protocol.LIST_THREAD, control.in.readUTF());
        assertEquals("dave", control.in.readUTF());

        control.out.writeUTF(Protocol.THREAD_MESSAGES);
        control.out.writeUTF("dave");
        control.out.writeInt(1);
        control.out.writeUTF("11111111-1111-1111-1111-111111111111");
        control.out.writeUTF("IN");
        control.out.writeUTF("TEXT");
        control.out.writeUTF("dave");
        control.out.writeUTF("carol");
        control.out.writeUTF("2026-11-17T08:00:00Z");
        control.out.writeUTF("hi");
        control.out.flush();

        assertEquals("dave:1", replies.poll(5, TimeUnit.SECONDS));
    }

    @Test
    void aFailedDownloadLeavesNoPartialFile() throws Exception {
        conn = login("carol", "carol");
        Path downloads = Files.createDirectory(dir.resolve("downloads"));
        CompletableFuture<AppError> failed = new CompletableFuture<>();

        conn.startParallelDownload("id-1", downloads, (d, t) -> {}, s -> {}, failed::complete,
                saved -> fail("download should not succeed"));

        Peer transfer = server.next();
        transfer.acceptTransferHello();
        transfer.out.writeUTF(Protocol.FILE);
        transfer.out.writeUTF("report.pdf");
        transfer.out.writeLong(1000);
        transfer.out.write(new byte[10]); // then the connection drops
        transfer.out.flush();
        transfer.socket.close();

        assertNotNull(failed.get(5, TimeUnit.SECONDS));
        try (var files = Files.list(downloads)) {
            assertEquals(List.of(), files.toList(), "truncated report.pdf was deleted");
        }
    }

    @Test
    void aDownloadIntoAFolderNeverOverwritesAndKeepsAllBytes() throws Exception {
        conn = login("carol", "carol");
        Files.writeString(dir.resolve("report.pdf"), "keep me");
        byte[] data = new byte[200_000];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i * 7);
        CompletableFuture<Path> saved = new CompletableFuture<>();

        conn.startParallelDownload("id-1", dir, (d, t) -> {}, s -> {},
                err -> saved.completeExceptionally(new AssertionError(err.toString())), saved::complete);

        Peer transfer = server.next();
        transfer.acceptTransferHello();
        transfer.out.writeUTF(Protocol.FILE);
        transfer.out.writeUTF("..\\report.pdf"); // a hostile name must stay inside the folder
        transfer.out.writeLong(data.length);
        transfer.out.write(data);
        transfer.out.flush();

        Path file = saved.get(5, TimeUnit.SECONDS);
        assertEquals(dir.resolve("report (1).pdf"), file);
        assertArrayEquals(data, Files.readAllBytes(file));
        assertEquals("keep me", Files.readString(dir.resolve("report.pdf")));
    }

    // ===== helpers =====

    private CompletableFuture<Void> uploadAsync(String to, Path file) {
        return CompletableFuture.runAsync(() -> {
            try {
                conn.sendFile(to, file, (done, total) -> {});
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });
    }

    private Future<ClientConnection> connectAsync(String user) {
        return connectAsync(user, known);
    }

    private Future<ClientConnection> connectAsync(String user, KnownServers trusted) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return new ClientConnection("127.0.0.1", server.port(), user, "secret1".toCharArray(), trusted);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });
    }

    private ClientConnection login(String user, String canonical) throws Exception {
        return login(user, canonical, known);
    }

    /** Connects and answers the login with {@code canonical} and a fixed token. */
    private ClientConnection login(String user, String canonical, KnownServers trusted) throws Exception {
        Future<ClientConnection> pending = connectAsync(user, trusted);
        Peer control = server.next();
        control.readHello();
        control.out.writeUTF(Protocol.HELLO_OK);
        control.out.writeUTF(canonical);
        control.out.writeUTF("token-123");
        control.out.flush();
        server.lastControl = control;
        return pending.get(5, TimeUnit.SECONDS);
    }

    /** Speaks TLS with the given certificate; the handshake runs when the test first reads from a {@link Peer}. */
    private static final class FakeServer implements Closeable {
        private final ServerSocket serverSocket;
        private final BlockingQueue<Socket> accepted = new LinkedBlockingQueue<>();
        Peer lastControl;

        FakeServer(ServerTls tls) throws IOException {
            serverSocket = tls.context().getServerSocketFactory().createServerSocket(0);
            Thread t = new Thread(() -> {
                try {
                    while (true) accepted.add(serverSocket.accept());
                } catch (IOException closed) {
                    // test finished
                }
            }, "fake-server");
            t.setDaemon(true);
            t.start();
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        Peer next() throws Exception {
            Socket s = accepted.poll(5, TimeUnit.SECONDS);
            assertNotNull(s, "client did not connect");
            return new Peer(s);
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
            for (Socket s : accepted) s.close();
        }
    }

    private static final class Peer {
        final Socket socket;
        final DataInputStream in;
        final DataOutputStream out;

        Peer(Socket socket) throws IOException {
            this.socket = socket;
            socket.setSoTimeout(5000);
            in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        }

        void readVersion() throws IOException {
            assertEquals(Protocol.CONNECT, in.readUTF());
            assertEquals(Protocol.VERSION, in.readInt());
        }

        void readHello() throws IOException {
            readVersion();
            assertEquals(Protocol.HELLO, in.readUTF());
            in.readUTF(); // user
            in.readUTF(); // password
        }

        void acceptTransferLogin() throws IOException {
            readVersion();
            assertEquals(Protocol.HELLO_TRANSFER, in.readUTF());
            in.readUTF(); // user
            assertEquals("token-123", in.readUTF());
            out.writeUTF(Protocol.HELLO_OK);
            out.flush();
        }

        void acceptTransferHello() throws IOException {
            acceptTransferLogin();
            assertEquals(Protocol.DOWNLOAD, in.readUTF());
            in.readUTF(); // file id
        }
    }
}
