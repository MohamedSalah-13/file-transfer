package com.hamza.filetransfer.client;

import com.hamza.filetransfer.shared.ErrorCode;
import com.hamza.filetransfer.shared.Protocol;
import javafx.application.Platform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
    Path dir;

    private FakeServer server;
    private ClientConnection conn;

    @BeforeAll
    static void startFx() {
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException alreadyStarted) {
            // another test class started it
        }
        Platform.setImplicitExit(false);
    }

    @BeforeEach
    void startServer() throws IOException {
        server = new FakeServer();
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
    void requestsDoNotWaitForAnUploadInProgress() throws Exception {
        conn = login("carol", "carol");
        Peer control = server.lastControl;
        conn.start();

        // The fake server reads the header and then stops reading, so the upload stalls
        // (holding the write lock) once the socket buffers are full.
        Path big = dir.resolve("big.bin");
        Files.write(big, new byte[64 * 1024 * 1024]);
        CompletableFuture<Void> upload = CompletableFuture.runAsync(() -> {
            try {
                conn.sendFile("dave", big, (done, total) -> {});
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });
        assertEquals(Protocol.SEND_FILE, control.in.readUTF());
        assertEquals("dave", control.in.readUTF());
        assertEquals("big.bin", control.in.readUTF());
        assertEquals(64L * 1024 * 1024, control.in.readLong());
        Thread.sleep(300);
        assertFalse(upload.isDone(), "upload is stalled");

        long start = System.nanoTime();
        conn.requestThread("dave");
        conn.requestUsers();
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertTrue(millis < 200, "requests returned in " + millis + " ms instead of waiting for the upload");
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
        CompletableFuture<AppError> failed = new CompletableFuture<>();

        conn.startParallelDownload("id-1", dir, (d, t) -> {}, s -> {}, failed::complete,
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
        try (var files = Files.list(dir)) {
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

    private Future<ClientConnection> connectAsync(String user) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return new ClientConnection("127.0.0.1", server.port(), user, "secret1".toCharArray());
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });
    }

    /** Connects and answers the handshake with {@code canonical} and a fixed token. */
    private ClientConnection login(String user, String canonical) throws Exception {
        Future<ClientConnection> pending = connectAsync(user);
        Peer control = server.next();
        control.readHello();
        control.out.writeUTF(Protocol.HELLO_OK);
        control.out.writeUTF(canonical);
        control.out.writeUTF("token-123");
        control.out.flush();
        server.lastControl = control;
        return pending.get(5, TimeUnit.SECONDS);
    }

    private static final class FakeServer implements Closeable {
        private final ServerSocket serverSocket = new ServerSocket(0);
        private final BlockingQueue<Socket> accepted = new LinkedBlockingQueue<>();
        Peer lastControl;

        FakeServer() throws IOException {
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

        void readHello() throws IOException {
            assertEquals(Protocol.HELLO, in.readUTF());
            in.readUTF(); // user
            in.readUTF(); // password
        }

        void acceptTransferHello() throws IOException {
            assertEquals(Protocol.HELLO_TRANSFER, in.readUTF());
            in.readUTF(); // user
            assertEquals("token-123", in.readUTF());
            out.writeUTF(Protocol.HELLO_OK);
            out.flush();
            assertEquals(Protocol.DOWNLOAD, in.readUTF());
            in.readUTF(); // file id
        }
    }
}
