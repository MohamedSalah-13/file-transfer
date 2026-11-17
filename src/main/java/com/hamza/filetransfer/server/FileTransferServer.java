package com.hamza.filetransfer.server;

import com.hamza.filetransfer.shared.Limits;
import com.hamza.filetransfer.shared.Protocol;
import com.hamza.filetransfer.shared.SafeNames;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class FileTransferServer {

    public record OnlineClient(String username, String token, DataOutputStream out, Object lock) {}

    private final int port;
    private final Path usersDir;
    private final AccountStore accounts;
    private final SecureRandom random = new SecureRandom();

    // CONTROL connections only
    private final Map<String, OnlineClient> online = new ConcurrentHashMap<>();

    private final Consumer<TransferSession> onNewSession;

    public FileTransferServer(int port, Path usersDir, AccountStore accounts, Consumer<TransferSession> onNewSession) {
        this.port = port;
        this.usersDir = usersDir.toAbsolutePath().normalize();
        this.accounts = accounts;
        this.onNewSession = onNewSession;
    }

    public void startAsync() {
        Thread t = new Thread(this::run, "server-acceptor");
        t.setDaemon(true);
        t.start();
    }

    private void run() {
        try {
            Files.createDirectories(usersDir);
            try (ServerSocket serverSocket = new ServerSocket(port)) {
                while (true) {
                    Socket s = serverSocket.accept();
                    new Thread(() -> handleClient(s), "client-" + s.getRemoteSocketAddress()).start();
                }
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

            Object lock = new Object();

            // ===== Handshake =====
            String cmd = in.readUTF();
            if (Protocol.HELLO.equals(cmd)) {
                // CONTROL connection: username + password
                String requested = SafeNames.normalizeUsername(in.readUTF());
                char[] password = in.readUTF().toCharArray();

                if (!SafeNames.isValidUsername(requested)) {
                    authFailed(out, lock, "Invalid username. Use 1-" + SafeNames.MAX_USERNAME_LENGTH
                            + " letters, digits, '_' or '-'.");
                    return;
                }

                AccountStore.Result auth;
                try {
                    auth = accounts.authenticate(requested, password);
                } finally {
                    Arrays.fill(password, '\0');
                }

                if (!auth.success()) {
                    String reason = switch (auth.status()) {
                        case WEAK_PASSWORD -> "New account: password must be "
                                + AccountStore.MIN_PASSWORD_LENGTH + "-" + AccountStore.MAX_PASSWORD_LENGTH + " characters.";
                        case LOCKED -> "Too many failed attempts. Try again in 30 seconds.";
                        default -> "Wrong username or password.";
                    };
                    Thread.sleep(500); // slow down online guessing
                    authFailed(out, lock, reason);
                    return;
                }

                username = auth.username();
                Files.createDirectories(userInboxDir(username));

                // Only one CONTROL connection per user
                OnlineClient candidate = new OnlineClient(username, newToken(), out, lock);
                if (online.putIfAbsent(username, candidate) != null) {
                    synchronized (lock) {
                        out.writeUTF(Protocol.HELLO_TAKEN);
                        out.flush();
                    }
                    return;
                }
                me = candidate;

                synchronized (lock) {
                    out.writeUTF(Protocol.HELLO_OK);
                    out.writeUTF(username);
                    out.writeUTF(me.token());
                    out.flush();
                }

            } else if (Protocol.HELLO_TRANSFER.equals(cmd)) {
                // TRANSFER connection: must present the token of a live CONTROL session
                String requested = SafeNames.normalizeUsername(in.readUTF());
                String token = in.readUTF();

                OnlineClient owner = requested == null ? null : online.get(requested);
                if (owner == null || !MessageDigest.isEqual(
                        owner.token().getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
                    authFailed(out, lock, "Invalid or expired session.");
                    return;
                }
                username = owner.username();

                synchronized (lock) {
                    out.writeUTF(Protocol.HELLO_OK);
                    out.flush();
                }

            } else {
                sendError(out, lock, "Expected HELLO");
                return;
            }

            boolean isControl = me != null;

            // ===== Command loop =====
            while (true) {
                String c = in.readUTF();

                if (Protocol.LIST_USERS.equals(c)) {
                    if (!isControl) {
                        sendError(out, lock, "LIST_USERS allowed only on CONTROL connection");
                        continue;
                    }
                    handleListUsers(out, lock);

                } else if (Protocol.SEND_FILE.equals(c)) {
                    handleSendFile(username, in, out, lock);

                } else if (Protocol.SEND_TEXT.equals(c)) {
                    handleSendText(username, in, out, lock);

                } else if (Protocol.LIST_MY_FILES.equals(c)) {
                    if (!isControl) {
                        sendError(out, lock, "LIST_MY_FILES allowed only on CONTROL connection");
                        continue;
                    }
                    handleListMyFiles(username, out, lock);

                } else if (Protocol.LIST_THREAD.equals(c)) {
                    if (!isControl) {
                        sendError(out, lock, "LIST_THREAD allowed only on CONTROL connection");
                        continue;
                    }
                    handleListThread(username, in, out, lock);

                } else if (Protocol.DOWNLOAD.equals(c)) {
                    handleDownload(username, in, out, lock);

                } else {
                    sendError(out, lock, "Unknown command: " + c);
                }
            }

        } catch (EOFException ignored) {
            // client disconnected
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (me != null) {
                online.remove(me.username(), me); // only remove our own entry
            }
        }
    }

    private String newToken() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** Validates a peer username from the client; returns null if invalid or no such account. */
    private String knownPeer(String raw) {
        String peer = SafeNames.normalizeUsername(raw);
        if (!SafeNames.isValidUsername(peer) || !accounts.exists(peer)) return null;
        return peer;
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
            sendError(out, lock, "File too large. Max is 200MB.");
            throw new IOException("Rejected oversized file");
        }

        if (toUser == null) {
            discard(in, fileSize); // keep the stream in sync, then report
            sendError(out, lock, "Unknown recipient.");
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
            session.setStatus("FAILED");
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

        OnlineClient targetControl = online.get(toUser);
        if (targetControl != null) {
            synchronized (targetControl.lock()) {
                targetControl.out().writeUTF(Protocol.INCOMING_FILE);
                targetControl.out().writeUTF(messageId);
                targetControl.out().writeUTF(fromUser);
                targetControl.out().writeUTF(fileName);
                targetControl.out().writeLong(fileSize);
                targetControl.out().writeUTF(ts.toString());
                targetControl.out().flush();
            }
        }

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
            sendError(out, lock, "Unknown recipient.");
            return;
        }

        if (text == null) text = "";
        text = text.trim();
        if (text.isEmpty()) {
            sendError(out, lock, "Empty text");
            return;
        }
        if (text.length() > 2000) {
            sendError(out, lock, "Text too long (max 2000 chars)");
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

        OnlineClient targetControl = online.get(toUser);
        if (targetControl != null) {
            synchronized (targetControl.lock()) {
                targetControl.out().writeUTF(Protocol.INCOMING_TEXT);
                targetControl.out().writeUTF(messageId);
                targetControl.out().writeUTF(fromUser);
                targetControl.out().writeUTF(ts.toString());
                targetControl.out().flush();
            }
        }
    }

    private void handleListThread(String username, DataInputStream in, DataOutputStream out, Object lock) throws Exception {
        String rawPeer = in.readUTF();
        String peer = knownPeer(rawPeer);
        if (peer == null) {
            sendError(out, lock, "Unknown user.");
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
                out.writeUTF(messageType);                     // TEXT / FILE
                out.writeUTF(pr.getProperty("fromUser"));
                out.writeUTF(pr.getProperty("toUser"));
                out.writeUTF(pr.getProperty("timestamp"));

                if ("TEXT".equals(messageType)) {
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
            sendError(out, lock, "Not found");
            return;
        }

        Path inbox = userInboxDir(username);
        Path meta = SafeNames.resolveInside(inbox, fileId + ".meta");
        Path bin = SafeNames.resolveInside(inbox, fileId + ".bin");

        if (!Files.exists(meta) || !Files.exists(bin)) {
            sendError(out, lock, "Not found");
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

    private void authFailed(DataOutputStream out, Object lock, String reason) throws IOException {
        synchronized (lock) {
            out.writeUTF(Protocol.AUTH_FAILED);
            out.writeUTF(reason);
            out.flush();
        }
    }

    private void sendError(DataOutputStream out, Object lock, String message) throws IOException {
        synchronized (lock) {
            out.writeUTF(Protocol.ERROR);
            out.writeUTF(message);
            out.flush();
        }
    }

    private Path userInboxDir(String username) {
        return userDir(username).resolve("inbox");
    }
}
