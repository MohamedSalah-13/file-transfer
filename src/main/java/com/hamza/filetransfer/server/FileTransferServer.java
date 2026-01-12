package com.hamza.filetransfer.server;

import com.hamza.filetransfer.shared.Limits;
import com.hamza.filetransfer.shared.Protocol;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class FileTransferServer {

    public record OnlineClient(String username, DataOutputStream out, Object lock) {}

    private final int port;
    private final Path usersDir;

    // CONTROL connections only
    private final Map<String, OnlineClient> online = new ConcurrentHashMap<>();

    private final Consumer<TransferSession> onNewSession;

    public FileTransferServer(int port, Path usersDir, Consumer<TransferSession> onNewSession) {
        this.port = port;
        this.usersDir = usersDir;
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
        boolean isControl = false;
        try (socket;
             DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {

            Object lock = new Object();

            // 1) HELLO
            String cmd = in.readUTF();
            if (!Protocol.HELLO.equals(cmd)) {
                sendError(out, lock, "Expected HELLO");
                return;
            }
            username = in.readUTF().trim();
            if (username.isBlank()) {
                sendError(out, lock, "Empty username");
                return;
            }

            Files.createDirectories(userInboxDir(username));

            synchronized (lock) {
                out.writeUTF(Protocol.HELLO_OK);
                out.flush();
            }

            // 2) MODE
            String modeCmd = in.readUTF();
            if (!Protocol.MODE.equals(modeCmd)) {
                sendError(out, lock, "Expected MODE after HELLO");
                return;
            }
            String mode = in.readUTF();

            if (Protocol.MODE_CONTROL.equals(mode)) {
                isControl = true;

                // Only one CONTROL connection per user
                OnlineClient prev = online.putIfAbsent(username, new OnlineClient(username, out, lock));
                if (prev != null) {
                    synchronized (lock) {
                        out.writeUTF(Protocol.HELLO_TAKEN);
                        out.flush();
                    }
                    return;
                }

            } else if (Protocol.MODE_TRANSFER.equals(mode)) {
                // TRANSFER connections are allowed in parallel. Do NOT register in 'online'.
                isControl = false;

            } else {
                sendError(out, lock, "Unknown MODE: " + mode);
                return;
            }

            // 3) Loop commands
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
            if (username != null && isControl) {
                online.remove(username);
            }
        }
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
        String toUser = in.readUTF().trim();
        String fileName = in.readUTF();
        long fileSize = in.readLong();

        if (fileSize < 0 || fileSize > Limits.MAX_FILE_BYTES) {
            sendError(out, lock, "File too large. Max is 200MB.");
            throw new IOException("Rejected oversized file");
        }

        String messageId = UUID.randomUUID().toString();
        Instant ts = Instant.now();

        TransferSession session = new TransferSession(fromUser, toUser, fileName, fileSize);
        session.setStatus("RECEIVING");
        onNewSession.accept(session);

        // Recipient inbox stores the binary for download
        Path inbox = userInboxDir(toUser);
        Files.createDirectories(inbox);

        Path bin = inbox.resolve(messageId + ".bin");
        Path metaInbox = inbox.resolve(messageId + ".meta");

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
        }

        session.setStatus("STORED");

        long epochMillis = ts.toEpochMilli();

        // inbox meta (recipient)
        String inboxMeta =
                "messageId=" + messageId + "\n" +
                        "fromUser=" + fromUser + "\n" +
                        "toUser=" + toUser + "\n" +
                        "fileName=" + fileName + "\n" +
                        "fileSize=" + fileSize + "\n" +
                        "timestamp=" + ts + "\n" +
                        "timestampMillis=" + epochMillis + "\n";
        Files.writeString(metaInbox, inboxMeta, StandardOpenOption.CREATE_NEW);

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

    private void handleSendText(String fromUser, DataInputStream in, DataOutputStream out, Object lock) throws Exception {
        String toUser = in.readUTF().trim();
        String text = in.readUTF();

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
        String peer = in.readUTF().trim();
        Path dir = userThreadDir(username, peer);
        Files.createDirectories(dir);

        List<Properties> messages = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.meta")) {
            for (Path p : ds) {
                Properties pr = new Properties();
                try (Reader r = Files.newBufferedReader(p)) {
                    pr.load(r);
                }
                messages.add(pr);
            }
        }

        messages.sort(Comparator.comparingLong(p -> Long.parseLong(p.getProperty("timestampMillis", "0"))));

        synchronized (lock) {
            out.writeUTF(Protocol.THREAD_MESSAGES);
            out.writeUTF(peer);
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

        Path meta = dir.resolve(messageId + ".meta");
        String content =
                "messageId=" + messageId + "\n" +
                        "messageType=TEXT\n" +
                        "direction=" + direction + "\n" +
                        "fromUser=" + fromUser + "\n" +
                        "toUser=" + toUser + "\n" +
                        "timestamp=" + timestamp + "\n" +
                        "timestampMillis=" + timestampMillis + "\n" +
                        "text=" + escape(text) + "\n";

        Files.writeString(meta, content, StandardOpenOption.CREATE_NEW);
    }

    // Update existing writeThreadMeta(...) for FILE to include messageType
    private void writeThreadMeta(String ownerUser, String peerUser, String messageId,
                                 String direction, String fromUser, String toUser,
                                 String fileName, long fileSize,
                                 String timestamp, long timestampMillis,
                                 boolean downloadable) throws IOException {

        Path dir = userThreadDir(ownerUser, peerUser);
        Files.createDirectories(dir);

        Path meta = dir.resolve(messageId + ".meta");
        String content =
                "messageId=" + messageId + "\n" +
                        "messageType=FILE\n" +
                        "direction=" + direction + "\n" +
                        "fromUser=" + fromUser + "\n" +
                        "toUser=" + toUser + "\n" +
                        "fileName=" + fileName + "\n" +
                        "fileSize=" + fileSize + "\n" +
                        "timestamp=" + timestamp + "\n" +
                        "timestampMillis=" + timestampMillis + "\n" +
                        "downloadable=" + downloadable + "\n";

        Files.writeString(meta, content, StandardOpenOption.CREATE_NEW);
    }

    private static String escape(String s) {
        // Properties format: we keep it simple and avoid newlines breaking the file
        return s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r");
    }


    private Path userThreadDir(String username, String peer) {
        return usersDir.resolve(username).resolve("threads").resolve(peer);
    }
    private void handleListMyFiles(String username, DataOutputStream out, Object lock) throws Exception {
        Path inbox = userInboxDir(username);
        Files.createDirectories(inbox);

        List<Path> metas = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(inbox, "*.meta")) {
            for (Path p : ds) metas.add(p);
        }

        synchronized (lock) {
            out.writeUTF(Protocol.MY_FILES);
            out.writeInt(metas.size());
            for (Path meta : metas) {
                Properties pr = new Properties();
                try (Reader r = Files.newBufferedReader(meta)) {
                    pr.load(r);
                }
                out.writeUTF(pr.getProperty("fileId"));
                out.writeUTF(pr.getProperty("fileName"));
                out.writeLong(Long.parseLong(pr.getProperty("fileSize")));
                out.writeUTF(pr.getProperty("fromUser"));
                out.writeUTF(pr.getProperty("timestamp"));
            }
            out.flush();
        }
    }

    private void handleDownload(String username, DataInputStream in, DataOutputStream out, Object lock) throws Exception {
        String fileId = in.readUTF();

        Path inbox = userInboxDir(username);
        Path meta = inbox.resolve(fileId + ".meta");
        Path bin = inbox.resolve(fileId + ".bin");

        if (!Files.exists(meta) || !Files.exists(bin)) {
            sendError(out, lock, "Not found");
            return;
        }

        Properties pr = new Properties();
        try (Reader r = Files.newBufferedReader(meta)) {
            pr.load(r);
        }

        String fileName = pr.getProperty("fileName");
        long fileSize = Long.parseLong(pr.getProperty("fileSize"));

        synchronized (lock) {
            out.writeUTF(Protocol.FILE);
            out.writeUTF(fileName);
            out.writeLong(fileSize);
            out.flush();
        }

        try (InputStream fileIn = Files.newInputStream(bin)) {
            byte[] buffer = new byte[Limits.BUFFER_SIZE];
            int read;
            while ((read = fileIn.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
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
        return usersDir.resolve(username).resolve("inbox");
    }
}
