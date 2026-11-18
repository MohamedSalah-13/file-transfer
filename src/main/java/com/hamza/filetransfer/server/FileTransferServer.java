package com.hamza.filetransfer.server;

import com.hamza.filetransfer.shared.ErrorCode;
import com.hamza.filetransfer.shared.Limits;
import com.hamza.filetransfer.shared.Protocol;
import com.hamza.filetransfer.shared.SafeNames;
import com.hamza.filetransfer.shared.Tls;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import java.io.*;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

public class FileTransferServer {

    /**
     * A logged-in CONTROL connection. Notifications for this user are written by its own
     * {@code pusher} thread, so a slow or dead recipient never blocks or breaks the sender.
     */
    public record OnlineClient(String username, String token, Socket socket, DataOutputStream out, Object lock,
                               ExecutorService pusher) {

        /** Drops the connection; its handler thread then sees the closed socket and cleans up. */
        void disconnect() {
            pusher.shutdownNow();
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    @FunctionalInterface
    private interface PushWriter {
        void write(DataOutputStream out) throws IOException;
    }

    /**
     * A client must finish the TLS handshake and send its login within this time, so connections
     * that never log in don't hold a thread forever. Logged-in sockets have no read timeout.
     */
    private static final int HANDSHAKE_TIMEOUT_MILLIS = 15_000;

    private final int port;
    private final Path usersDir;
    private final AccountStore accounts;
    private final SSLContext tls;
    private final SecureRandom random = new SecureRandom();

    // CONTROL connections only
    private final Map<String, OnlineClient> online = new ConcurrentHashMap<>();

    private final Consumer<TransferSession> onNewSession;

    /** Every connection is TLS 1.3 with the server certificate in {@code tls} (see {@link ServerTls}). */
    public FileTransferServer(int port, Path usersDir, AccountStore accounts, SSLContext tls,
                              Consumer<TransferSession> onNewSession) {
        this.port = port;
        this.usersDir = usersDir.toAbsolutePath().normalize();
        this.accounts = accounts;
        this.tls = tls;
        this.onNewSession = onNewSession;
    }

    /**
     * Opens the port right away, so a problem such as a port already in use is thrown here
     * (a {@link java.net.BindException}), then accepts clients on a background thread.
     */
    public void startAsync() throws IOException {
        Files.createDirectories(usersDir);
        SSLServerSocket serverSocket = (SSLServerSocket) tls.getServerSocketFactory().createServerSocket(port);
        serverSocket.setEnabledProtocols(new String[]{Tls.PROTOCOL});
        Thread t = new Thread(() -> acceptLoop(serverSocket), "server-acceptor");
        t.setDaemon(true);
        t.start();
    }

    private void acceptLoop(SSLServerSocket serverSocket) {
        try (serverSocket) {
            while (true) {
                Socket s = serverSocket.accept();
                new Thread(() -> handleClient(s), "client-" + s.getRemoteSocketAddress()).start();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void handleClient(Socket socket) {
        String username = null;
        OnlineClient me = null;
        try (socket;
             DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {

            socket.setKeepAlive(true); // let the OS notice peers that vanished without closing
            socket.setSoTimeout(HANDSHAKE_TIMEOUT_MILLIS);
            ((SSLSocket) socket).startHandshake();
            Object lock = new Object();

            // ===== Version =====
            String cmd = in.readUTF();
            if (Protocol.CONNECT.equals(cmd)) {
                int clientVersion = in.readInt();
                if (clientVersion != Protocol.VERSION) {
                    authFailed(out, lock, ErrorCode.VERSION_MISMATCH,
                            String.valueOf(Protocol.VERSION), String.valueOf(clientVersion));
                    return;
                }
                cmd = in.readUTF();
            } else if (Protocol.HELLO.equals(cmd) || Protocol.HELLO_TRANSFER.equals(cmd)) {
                // a client from before versioning: it can read this error but not the new protocol
                authFailed(out, lock, ErrorCode.VERSION_MISMATCH, String.valueOf(Protocol.VERSION), "0");
                return;
            }

            // ===== Handshake =====
            if (Protocol.HELLO.equals(cmd)) {
                // CONTROL connection: username + password
                String requested = SafeNames.normalizeUsername(in.readUTF());
                char[] password = in.readUTF().toCharArray();

                if (!SafeNames.isValidUsername(requested)) {
                    authFailed(out, lock, ErrorCode.INVALID_USERNAME, String.valueOf(SafeNames.MAX_USERNAME_LENGTH));
                    return;
                }

                AccountStore.Result auth;
                try {
                    auth = accounts.authenticate(requested, password);
                } finally {
                    Arrays.fill(password, '\0');
                }

                if (!auth.success()) {
                    Thread.sleep(500); // slow down online guessing
                    switch (auth.status()) {
                        case WEAK_PASSWORD -> authFailed(out, lock, ErrorCode.WEAK_PASSWORD,
                                String.valueOf(AccountStore.MIN_PASSWORD_LENGTH), String.valueOf(AccountStore.MAX_PASSWORD_LENGTH));
                        case LOCKED -> authFailed(out, lock, ErrorCode.ACCOUNT_LOCKED,
                                String.valueOf(AccountStore.LOCKOUT_SECONDS));
                        default -> authFailed(out, lock, ErrorCode.WRONG_CREDENTIALS);
                    }
                    return;
                }

                username = auth.username();
                Files.createDirectories(userInboxDir(username));

                // Only one CONTROL connection per user: a new login (with the right password) replaces
                // the old one. Otherwise a client that vanished without closing its socket (Wi-Fi drop,
                // sleep) would keep the name "online" and lock its owner out.
                me = new OnlineClient(username, newToken(), socket, out, lock, newPusher(username));
                OnlineClient previous;
                synchronized (lock) { // pushes to us wait for this lock, so HELLO_OK is always first
                    previous = online.put(username, me);
                    out.writeUTF(Protocol.HELLO_OK);
                    out.writeUTF(username);
                    out.writeUTF(me.token());
                    out.flush();
                }
                if (previous != null) previous.disconnect();

            } else if (Protocol.HELLO_TRANSFER.equals(cmd)) {
                // TRANSFER connection: must present the token of a live CONTROL session
                String requested = SafeNames.normalizeUsername(in.readUTF());
                String token = in.readUTF();

                // `online` is keyed by the canonical name, like everything after login.
                String canonical = SafeNames.isValidUsername(requested) ? accounts.canonicalName(requested) : null;
                OnlineClient owner = canonical == null ? null : online.get(canonical);
                if (owner == null || !MessageDigest.isEqual(
                        owner.token().getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
                    authFailed(out, lock, ErrorCode.INVALID_SESSION);
                    return;
                }
                username = owner.username();

                synchronized (lock) {
                    out.writeUTF(Protocol.HELLO_OK);
                    out.flush();
                }

            } else {
                sendError(out, lock, ErrorCode.BAD_HANDSHAKE);
                return;
            }
            socket.setSoTimeout(0); // logged in: CONTROL waits for commands, TRANSFER may pause mid-file

            boolean isControl = me != null;

            // ===== Command loop =====
            while (true) {
                String c = in.readUTF();

                if (Protocol.LIST_USERS.equals(c)) {
                    if (!isControl) {
                        sendError(out, lock, ErrorCode.CONTROL_ONLY, Protocol.LIST_USERS);
                        continue;
                    }
                    handleListUsers(out, lock);

                } else if (Protocol.SEND_FILE.equals(c)) {
                    handleSendFile(username, in, out, lock);

                } else if (Protocol.SEND_TEXT.equals(c)) {
                    handleSendText(username, in, out, lock);

                } else if (Protocol.LIST_MY_FILES.equals(c)) {
                    if (!isControl) {
                        sendError(out, lock, ErrorCode.CONTROL_ONLY, Protocol.LIST_MY_FILES);
                        continue;
                    }
                    handleListMyFiles(username, out, lock);

                } else if (Protocol.LIST_THREAD.equals(c)) {
                    if (!isControl) {
                        sendError(out, lock, ErrorCode.CONTROL_ONLY, Protocol.LIST_THREAD);
                        continue;
                    }
                    handleListThread(username, in, out, lock);

                } else if (Protocol.DELETE_MESSAGE.equals(c)) {
                    if (!isControl) {
                        in.readUTF(); in.readUTF(); in.readUTF(); // keep the stream in sync
                        sendError(out, lock, ErrorCode.CONTROL_ONLY, Protocol.DELETE_MESSAGE);
                        continue;
                    }
                    handleDeleteMessage(username, in, out, lock);

                } else if (Protocol.DOWNLOAD.equals(c)) {
                    handleDownload(username, in, out, lock);

                } else {
                    sendError(out, lock, ErrorCode.UNKNOWN_COMMAND, c);
                }
            }

        } catch (EOFException | SocketException ignored) {
            // client disconnected, or its socket was closed (replaced by a new login / dead push target)
        } catch (SSLException | SocketTimeoutException e) {
            // TLS handshake failed: a 1.x client without TLS, a client that has not trusted our
            // certificate yet (it asks its user and reconnects), or one that never finished the handshake
            // or never sent its login.
            System.err.println("TLS handshake or login failed with " + socket.getRemoteSocketAddress() + ": " + e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (me != null) {
                online.remove(me.username(), me); // only remove our own entry
                me.pusher().shutdownNow();
            }
        }
    }

    private static ExecutorService newPusher(String username) {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "push-" + username);
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Sends a notification to {@code toUser} if online, without blocking the caller.
     * A write failure means the recipient's socket is dead, so that connection is dropped.
     */
    private void push(String toUser, PushWriter writer) {
        OnlineClient target = online.get(toUser);
        if (target == null) return;
        try {
            target.pusher().execute(() -> {
                try {
                    synchronized (target.lock()) {
                        writer.write(target.out());
                        target.out().flush();
                    }
                } catch (IOException e) {
                    target.disconnect();
                }
            });
        } catch (RejectedExecutionException ignored) {
            // the recipient is disconnecting
        }
    }

    private String newToken() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /**
     * Validates a peer username from the client and returns the account's canonical spelling
     * (so "ALI" and "Ali" use the same folders and online entry); null if invalid or no such account.
     */
    private String knownPeer(String raw) {
        String peer = SafeNames.normalizeUsername(raw);
        if (!SafeNames.isValidUsername(peer)) return null;
        return accounts.canonicalName(peer);
    }

    private void handleListUsers(DataOutputStream out, Object lock) throws IOException {
        List<String> users = new ArrayList<>(online.keySet());
        Collections.sort(users);

        synchronized (lock) {
            out.writeUTF(Protocol.USERS);
            out.writeInt(users.size());
            for (String u : users) out.writeUTF(u);
            out.flush();
        }
    }

    private void handleSendFile(String fromUser, DataInputStream in, DataOutputStream out, Object lock) throws Exception {
        String toUser = knownPeer(in.readUTF());
        String fileName = SafeNames.sanitizeFileName(in.readUTF());
        long fileSize = in.readLong();

        if (fileSize < 0 || fileSize > Limits.MAX_FILE_BYTES) {
            sendError(out, lock, ErrorCode.FILE_TOO_LARGE, String.valueOf(Limits.MAX_FILE_BYTES / (1024 * 1024)));
            throw new IOException("Rejected oversized file");
        }

        if (toUser == null || toUser.equals(fromUser)) {
            discard(in, fileSize); // keep the stream in sync, then report
            sendError(out, lock, toUser == null ? ErrorCode.UNKNOWN_RECIPIENT : ErrorCode.SELF_RECIPIENT);
            return;
        }

        String messageId = UUID.randomUUID().toString();
        Instant ts = Instant.now();

        TransferSession session = new TransferSession(fromUser, toUser, fileName, fileSize);
        session.setStatus("RECEIVING");
        onNewSession.accept(session);

        // Recipient inbox stores the binary for download
        Path inbox = userInboxDir(toUser);
        Files.createDirectories(inbox);

        Path bin = SafeNames.resolveInside(inbox, messageId + ".bin");
        Path metaInbox = SafeNames.resolveInside(inbox, messageId + ".meta");

        try (OutputStream fileOut = Files.newOutputStream(bin, StandardOpenOption.CREATE_NEW)) {
            byte[] buffer = new byte[Limits.BUFFER_SIZE];
            long received = 0;

            while (received < fileSize) {
                int toRead = (int) Math.min(buffer.length, fileSize - received);
                int read = in.read(buffer, 0, toRead);
                if (read == -1) throw new EOFException("Disconnected during upload");
                fileOut.write(buffer, 0, read);
                received += read;

                session.setDoneBytes(received);
            }
        } catch (IOException e) {
            Files.deleteIfExists(bin); // don't leave half-written files behind
            session.setStatus("INTERRUPTED"); // the sender cancelled or the connection dropped
            throw e;
        }

        session.setStatus("STORED");

        long epochMillis = ts.toEpochMilli();

        // inbox meta (recipient)
        Properties inboxMeta = new Properties();
        inboxMeta.setProperty("messageId", messageId);
        inboxMeta.setProperty("fromUser", fromUser);
        inboxMeta.setProperty("toUser", toUser);
        inboxMeta.setProperty("fileName", fileName);
        inboxMeta.setProperty("fileSize", Long.toString(fileSize));
        inboxMeta.setProperty("timestamp", ts.toString());
        inboxMeta.setProperty("timestampMillis", Long.toString(epochMillis));
        writeMeta(metaInbox, inboxMeta);

        // Thread meta for recipient (IN)
        writeThreadMeta(toUser, fromUser, messageId, "IN", fromUser, toUser, fileName, fileSize, ts.toString(), epochMillis, true);

        // Thread meta for sender (OUT)
        writeThreadMeta(fromUser, toUser, messageId, "OUT", fromUser, toUser, fileName, fileSize, ts.toString(), epochMillis, false);

        synchronized (lock) {
            out.writeUTF(Protocol.SEND_OK);
            out.writeUTF(messageId);
            out.flush();
        }

        push(toUser, o -> {
            o.writeUTF(Protocol.INCOMING_FILE);
            o.writeUTF(messageId);
            o.writeUTF(fromUser);
            o.writeUTF(fileName);
            o.writeLong(fileSize);
            o.writeUTF(ts.toString());
        });

        session.setStatus("DONE");
    }

    private static void discard(DataInputStream in, long bytes) throws IOException {
        byte[] buffer = new byte[Limits.BUFFER_SIZE];
        long left = bytes;
        while (left > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, left));
            if (read == -1) throw new EOFException("Disconnected during upload");
            left -= read;
        }
    }

    private void handleSendText(String fromUser, DataInputStream in, DataOutputStream out, Object lock) throws Exception {
        String toUser = knownPeer(in.readUTF());
        String text = in.readUTF();

        if (toUser == null) {
            sendError(out, lock, ErrorCode.UNKNOWN_RECIPIENT);
            return;
        }
        if (toUser.equals(fromUser)) {
            // Both copies (IN and OUT) would be the same file in threads/<me>/<me>/.
            sendError(out, lock, ErrorCode.SELF_RECIPIENT);
            return;
        }

        if (text == null) text = "";
        text = text.trim();
        if (text.isEmpty()) {
            sendError(out, lock, ErrorCode.EMPTY_TEXT);
            return;
        }
        if (text.length() > Limits.MAX_TEXT_CHARS) {
            sendError(out, lock, ErrorCode.TEXT_TOO_LONG, String.valueOf(Limits.MAX_TEXT_CHARS));
            return;
        }

        String messageId = UUID.randomUUID().toString();
        Instant ts = Instant.now();
        long epochMillis = ts.toEpochMilli();

        // Thread meta for recipient (IN)
        writeThreadMetaText(toUser, fromUser, messageId, "IN", fromUser, toUser, text, ts.toString(), epochMillis);

        // Thread meta for sender (OUT)
        writeThreadMetaText(fromUser, toUser, messageId, "OUT", fromUser, toUser, text, ts.toString(), epochMillis);

        synchronized (lock) {
            out.writeUTF(Protocol.SEND_OK);
            out.writeUTF(messageId);
            out.flush();
        }

        push(toUser, o -> {
            o.writeUTF(Protocol.INCOMING_TEXT);
            o.writeUTF(messageId);
            o.writeUTF(fromUser);
            o.writeUTF(ts.toString());
        });
    }

    private void handleListThread(String username, DataInputStream in, DataOutputStream out, Object lock) throws Exception {
        String rawPeer = in.readUTF();
        String peer = knownPeer(rawPeer);
        if (peer == null) {
            sendError(out, lock, ErrorCode.UNKNOWN_USER);
            return;
        }

        Path dir = userThreadDir(username, peer);
        Files.createDirectories(dir);

        List<Properties> messages = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.meta")) {
            for (Path p : ds) {
                messages.add(readMeta(p));
            }
        }

        messages.sort(Comparator.comparingLong(p -> Long.parseLong(p.getProperty("timestampMillis", "0"))));

        synchronized (lock) {
            out.writeUTF(Protocol.THREAD_MESSAGES);
            out.writeUTF(rawPeer); // echo what the client asked for, so it can match its UI state
            out.writeInt(messages.size());

            for (Properties pr : messages) {
                String messageType = pr.getProperty("messageType", "FILE"); // backward compatible

                out.writeUTF(pr.getProperty("messageId"));
                out.writeUTF(pr.getProperty("direction"));     // IN / OUT
                out.writeUTF(messageType);                     // TEXT / FILE / DELETED
                out.writeUTF(pr.getProperty("fromUser"));
                out.writeUTF(pr.getProperty("toUser"));
                out.writeUTF(pr.getProperty("timestamp"));

                if (DELETED.equals(messageType)) {
                    // a placeholder: nothing else is kept
                } else if ("TEXT".equals(messageType)) {
                    out.writeUTF(pr.getProperty("text", ""));
                } else {
                    out.writeUTF(pr.getProperty("fileName"));
                    out.writeLong(Long.parseLong(pr.getProperty("fileSize")));
                    out.writeBoolean(Boolean.parseBoolean(pr.getProperty("downloadable", "false")));
                }
            }
            out.flush();
        }
    }

    /** messageType of a message deleted for everyone: the thread keeps who, when and a placeholder. */
    private static final String DELETED = "DELETED";
    /** One delete at a time: the two copies of a message must not be changed by two deletes at once. */
    private final Object deleteLock = new Object();

    /**
     * DELETE_MESSAGE &lt;peer&gt; &lt;messageId&gt; &lt;scope&gt; (see {@link Protocol#DELETE_MESSAGE}).
     * For me: removes the caller's copy, and for a received file its bytes in the caller's inbox.
     * For everyone: only on a message the caller sent; both copies become DELETED placeholders,
     * the file's bytes leave the recipient's inbox, and the recipient gets MESSAGE_DELETED.
     */
    private void handleDeleteMessage(String username, DataInputStream in, DataOutputStream out, Object lock) throws Exception {
        String rawPeer = in.readUTF();
        String messageId = in.readUTF();
        String scope = in.readUTF();

        String peer = knownPeer(rawPeer);
        if (peer == null) {
            sendError(out, lock, ErrorCode.UNKNOWN_USER);
            return;
        }
        if (!Protocol.DELETE_FOR_ME.equals(scope) && !Protocol.DELETE_FOR_EVERYONE.equals(scope)) {
            sendError(out, lock, ErrorCode.BAD_DELETE_SCOPE, scope);
            return;
        }
        if (!SafeNames.isValidMessageId(messageId)) { // it becomes a file name below
            sendError(out, lock, ErrorCode.MESSAGE_NOT_FOUND);
            return;
        }

        boolean notifyPeer = false;
        synchronized (deleteLock) {
            Path mine = SafeNames.resolveInside(userThreadDir(username, peer), messageId + ".meta");
            if (!Files.isRegularFile(mine)) {
                sendError(out, lock, ErrorCode.MESSAGE_NOT_FOUND);
                return;
            }
            Properties meta = readMeta(mine);
            String type = meta.getProperty("messageType", "FILE");
            boolean sentByMe = "OUT".equals(meta.getProperty("direction"));

            if (Protocol.DELETE_FOR_ME.equals(scope)) {
                Files.deleteIfExists(mine);
                if (!sentByMe && "FILE".equals(type)) deleteInboxCopy(username, messageId);
            } else {
                if (!sentByMe) {
                    sendError(out, lock, ErrorCode.NOT_YOUR_MESSAGE);
                    return;
                }
                if (!DELETED.equals(type)) {
                    replaceWithPlaceholder(mine, meta);
                    Path theirs = SafeNames.resolveInside(userThreadDir(peer, username), messageId + ".meta");
                    if (Files.isRegularFile(theirs)) replaceWithPlaceholder(theirs, readMeta(theirs));
                    if ("FILE".equals(type)) deleteInboxCopy(peer, messageId);
                    notifyPeer = true;
                }
            }
        }

        synchronized (lock) {
            out.writeUTF(Protocol.DELETE_OK);
            out.writeUTF(messageId);
            out.flush();
        }
        if (notifyPeer) {
            push(peer, o -> {
                o.writeUTF(Protocol.MESSAGE_DELETED);
                o.writeUTF(messageId);
                o.writeUTF(username);
            });
        }
    }

    /** Keeps only who, when and the id; the text or file name is gone from the server. */
    private static void replaceWithPlaceholder(Path metaFile, Properties meta) throws IOException {
        Properties p = new Properties();
        for (String key : List.of("messageId", "direction", "fromUser", "toUser", "timestamp", "timestampMillis")) {
            String v = meta.getProperty(key);
            if (v != null) p.setProperty(key, v);
        }
        p.setProperty("messageType", DELETED);
        p.setProperty("deletedAt", Instant.now().toString());
        Path tmp = Files.createTempFile(metaFile.getParent(), "deleted", ".tmp");
        try {
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                p.store(w, null);
            }
            Files.move(tmp, metaFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** The meta first: once it is gone, DOWNLOAD reports file_not_found even if the bytes linger. */
    private void deleteInboxCopy(String owner, String messageId) throws IOException {
        Path inbox = userInboxDir(owner);
        Files.deleteIfExists(SafeNames.resolveInside(inbox, messageId + ".meta"));
        try {
            Files.deleteIfExists(SafeNames.resolveInside(inbox, messageId + ".bin"));
        } catch (IOException e) {
            // e.g. still open by a download in progress on Windows; the meta is gone, so nobody can fetch it
            System.err.println("Could not delete " + messageId + ".bin yet: " + e.getMessage());
        }
    }

    private void writeThreadMetaText(String ownerUser, String peerUser, String messageId,
                                     String direction, String fromUser, String toUser,
                                     String text, String timestamp, long timestampMillis) throws IOException {

        Path dir = userThreadDir(ownerUser, peerUser);
        Files.createDirectories(dir);

        Properties p = new Properties();
        p.setProperty("messageId", messageId);
        p.setProperty("messageType", "TEXT");
        p.setProperty("direction", direction);
        p.setProperty("fromUser", fromUser);
        p.setProperty("toUser", toUser);
        p.setProperty("timestamp", timestamp);
        p.setProperty("timestampMillis", Long.toString(timestampMillis));
        p.setProperty("text", text);
        writeMeta(SafeNames.resolveInside(dir, messageId + ".meta"), p);
    }

    private void writeThreadMeta(String ownerUser, String peerUser, String messageId,
                                 String direction, String fromUser, String toUser,
                                 String fileName, long fileSize,
                                 String timestamp, long timestampMillis,
                                 boolean downloadable) throws IOException {

        Path dir = userThreadDir(ownerUser, peerUser);
        Files.createDirectories(dir);

        Properties p = new Properties();
        p.setProperty("messageId", messageId);
        p.setProperty("messageType", "FILE");
        p.setProperty("direction", direction);
        p.setProperty("fromUser", fromUser);
        p.setProperty("toUser", toUser);
        p.setProperty("fileName", fileName);
        p.setProperty("fileSize", Long.toString(fileSize));
        p.setProperty("timestamp", timestamp);
        p.setProperty("timestampMillis", Long.toString(timestampMillis));
        p.setProperty("downloadable", Boolean.toString(downloadable));
        writeMeta(SafeNames.resolveInside(dir, messageId + ".meta"), p);
    }

    /** Properties.store escapes '=', ':', newlines etc., so values cannot inject extra keys. */
    private static void writeMeta(Path file, Properties p) throws IOException {
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
            p.store(w, null);
        }
    }

    private static Properties readMeta(Path file) throws IOException {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        return p;
    }

    private Path userDir(String username) {
        return SafeNames.resolveInside(usersDir, username);
    }

    private Path userThreadDir(String username, String peer) {
        Path threads = userDir(username).resolve("threads");
        return SafeNames.resolveInside(threads, peer);
    }

    private void handleListMyFiles(String username, DataOutputStream out, Object lock) throws Exception {
        Path inbox = userInboxDir(username);
        Files.createDirectories(inbox);

        List<Properties> metas = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(inbox, "*.meta")) {
            for (Path p : ds) metas.add(readMeta(p));
        }

        synchronized (lock) {
            out.writeUTF(Protocol.MY_FILES);
            out.writeInt(metas.size());
            for (Properties pr : metas) {
                out.writeUTF(pr.getProperty("messageId", pr.getProperty("fileId", "")));
                out.writeUTF(pr.getProperty("fileName", "file"));
                out.writeLong(Long.parseLong(pr.getProperty("fileSize", "0")));
                out.writeUTF(pr.getProperty("fromUser", ""));
                out.writeUTF(pr.getProperty("timestamp", ""));
            }
            out.flush();
        }
    }

    private void handleDownload(String username, DataInputStream in, DataOutputStream out, Object lock) throws Exception {
        String fileId = in.readUTF();
        if (!SafeNames.isValidMessageId(fileId)) {
            sendError(out, lock, ErrorCode.FILE_NOT_FOUND);
            return;
        }

        Path inbox = userInboxDir(username);
        Path meta = SafeNames.resolveInside(inbox, fileId + ".meta");
        Path bin = SafeNames.resolveInside(inbox, fileId + ".bin");

        if (!Files.exists(meta) || !Files.exists(bin)) {
            sendError(out, lock, ErrorCode.FILE_NOT_FOUND);
            return;
        }

        Properties pr = readMeta(meta);

        String fileName = SafeNames.sanitizeFileName(pr.getProperty("fileName"));
        long fileSize = Files.size(bin); // trust the actual file, not the metadata

        synchronized (lock) {
            out.writeUTF(Protocol.FILE);
            out.writeUTF(fileName);
            out.writeLong(fileSize);
            out.flush();
        }

        try (InputStream fileIn = Files.newInputStream(bin)) {
            byte[] buffer = new byte[Limits.BUFFER_SIZE];
            long left = fileSize;
            while (left > 0) {
                int read = fileIn.read(buffer, 0, (int) Math.min(buffer.length, left));
                if (read == -1) break;
                out.write(buffer, 0, read);
                left -= read;
            }
            out.flush();
        }
    }

    private void authFailed(DataOutputStream out, Object lock, String code, String... args) throws IOException {
        writeError(out, lock, Protocol.AUTH_FAILED, code, args);
    }

    private void sendError(DataOutputStream out, Object lock, String code, String... args) throws IOException {
        writeError(out, lock, Protocol.ERROR, code, args);
    }

    /** {@code <type> <code> <argCount> <arg...>}; the client translates the code (see {@link ErrorCode}). */
    private static void writeError(DataOutputStream out, Object lock, String type, String code, String... args) throws IOException {
        synchronized (lock) {
            out.writeUTF(type);
            out.writeUTF(code);
            out.writeInt(args.length);
            for (String a : args) out.writeUTF(a);
            out.flush();
        }
    }

    private Path userInboxDir(String username) {
        return userDir(username).resolve("inbox");
    }
}
