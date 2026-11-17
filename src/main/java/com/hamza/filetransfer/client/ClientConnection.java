package com.hamza.filetransfer.client;

import com.hamza.filetransfer.shared.ErrorCode;
import com.hamza.filetransfer.shared.Limits;
import com.hamza.filetransfer.shared.Protocol;
import com.hamza.filetransfer.shared.SafeNames;
import javafx.application.Platform;

import java.io.*;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class ClientConnection implements Closeable {

    private final String host;
    private final int port;
    private final String username;   // canonical name returned by the server
    private final String sessionToken; // authorizes extra TRANSFER sockets

    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final Object writeLock = new Object();

    private volatile boolean running = true;

    // UI callbacks
    private Consumer<AppError> onError = err -> {
    };
    private Consumer<List<String>> onUsers = users -> {
    };
    private Consumer<List<InboxFile>> onMyFiles = files -> {
    };
    private Consumer<InboxFile> onIncoming = file -> {
    };

    private Consumer<List<ChatMessage>> onThreadMessages = msgs -> {
    };
    private Consumer<String> onThreadPeer = peer -> {
    };

    private Consumer<IncomingTextEvent> onIncomingText = evt -> {
    };

    /**
     * Opens the CONTROL connection and logs in. A new username is registered with this password
     * on first login; afterwards the same password is required.
     */
    public ClientConnection(String host, int port, String username, char[] password) throws Exception {
        this.host = host;
        this.port = port;

        this.socket = new Socket(host, port);
        try {
            this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

            // HELLO <username> <password>
            synchronized (writeLock) {
                out.writeUTF(Protocol.HELLO);
                out.writeUTF(username);
                out.writeUTF(new String(password));
                out.flush();
            }

            String resp = in.readUTF();
            if (Protocol.HELLO_TAKEN.equals(resp)) {
                throw new AppException(AppError.ALREADY_ONLINE);
            }
            if (Protocol.AUTH_FAILED.equals(resp) || Protocol.ERROR.equals(resp)) {
                throw new AppException(AppError.read(in));
            }
            if (!Protocol.HELLO_OK.equals(resp)) {
                throw new AppException(AppError.PROTOCOL_ERROR, resp);
            }
            this.username = in.readUTF();
            this.sessionToken = in.readUTF();
        } catch (Exception e) {
            socket.close();
            throw e;
        } finally {
            Arrays.fill(password, '\0');
        }

        Thread listener = new Thread(this::listenLoop, "client-control-listener");
        listener.setDaemon(true);
        listener.start();
    }

    public void setOnIncomingText(Consumer<IncomingTextEvent> onIncomingText) {
        this.onIncomingText = onIncomingText;
    }

    public void setOnThreadMessages(Consumer<List<ChatMessage>> onThreadMessages) {
        this.onThreadMessages = onThreadMessages;
    }

    public void setOnThreadPeer(Consumer<String> onThreadPeer) {
        this.onThreadPeer = onThreadPeer;
    }

    private void listenLoop() {
        try {
            while (running) {
                String type = in.readUTF();

                if (Protocol.ERROR.equals(type)) {
                    AppError err = AppError.read(in);
                    Platform.runLater(() -> onError.accept(err));

                } else if (Protocol.USERS.equals(type)) {
                    int n = in.readInt();
                    List<String> users = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) users.add(in.readUTF());
                    Platform.runLater(() -> onUsers.accept(users));

                } else if (Protocol.MY_FILES.equals(type)) {
                    int n = in.readInt();
                    List<InboxFile> files = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) {
                        String fileId = in.readUTF();
                        String fileName = in.readUTF();
                        long size = in.readLong();
                        String fromUser = in.readUTF();
                        String ts = in.readUTF();
                        files.add(new InboxFile(fileId, fileName, size, fromUser, ts));
                    }
                    Platform.runLater(() -> onMyFiles.accept(files));

                } else if (Protocol.THREAD_MESSAGES.equals(type)) {
                    String peer = in.readUTF();
                    int n = in.readInt();
                    List<ChatMessage> msgs = new ArrayList<>(n);

                    for (int i = 0; i < n; i++) {
                        String messageId = in.readUTF();
                        String direction = in.readUTF();     // IN / OUT
                        String messageType = in.readUTF();   // TEXT / FILE
                        String fromUser = in.readUTF();
                        String toUser = in.readUTF();
                        String ts = in.readUTF();

                        boolean outgoing = "OUT".equals(direction);

                        if ("TEXT".equals(messageType)) {
                            String textBody = in.readUTF();
                            msgs.add(ChatMessage.text(messageId, outgoing, fromUser, toUser, ts, textBody));
                        } else {
                            String fileName = in.readUTF();
                            long fileSize = in.readLong();
                            boolean downloadable = in.readBoolean();
                            msgs.add(ChatMessage.file(messageId, outgoing, fromUser, toUser, ts, fileName, fileSize, downloadable));
                        }
                    }

                    Platform.runLater(() -> {
                        onThreadPeer.accept(peer);
                        onThreadMessages.accept(msgs);
                    });

                } else if (Protocol.INCOMING_FILE.equals(type)) {
                    String fileId = in.readUTF();
                    String fromUser = in.readUTF();
                    String fileName = in.readUTF();
                    long size = in.readLong();
                    String ts = in.readUTF();
                    InboxFile f = new InboxFile(fileId, fileName, size, fromUser, ts);
                    Platform.runLater(() -> onIncoming.accept(f));

                } else if (Protocol.INCOMING_TEXT.equals(type)) {
                    String messageId = in.readUTF();
                    String fromUser = in.readUTF();
                    String ts = in.readUTF();
                    IncomingTextEvent evt = new IncomingTextEvent(messageId, fromUser, ts);
                    Platform.runLater(() -> onIncomingText.accept(evt));

                } else if (Protocol.SEND_OK.equals(type)) {
                    String messageId = in.readUTF();

                } else {
                    Platform.runLater(() -> onError.accept(new AppError(AppError.PROTOCOL_ERROR, type)));
                }
            }
        } catch (EOFException ignored) {
            if (running) Platform.runLater(() -> onError.accept(new AppError(AppError.DISCONNECTED)));
        } catch (Exception e) {
            if (running) Platform.runLater(() -> onError.accept(AppError.from(e)));
        }
    }

    public void requestThread(String peer) throws Exception {
        synchronized (writeLock) {
            out.writeUTF(Protocol.LIST_THREAD);
            out.writeUTF(peer);
            out.flush();
        }
    }

    public void setOnError(Consumer<AppError> onError) {
        this.onError = onError;
    }

    public void setOnUsers(Consumer<List<String>> onUsers) {
        this.onUsers = onUsers;
    }

    public void setOnMyFiles(Consumer<List<InboxFile>> onMyFiles) {
        this.onMyFiles = onMyFiles;
    }

    public void setOnIncoming(Consumer<InboxFile> onIncoming) {
        this.onIncoming = onIncoming;
    }

    public void requestUsers() throws Exception {
        synchronized (writeLock) {
            out.writeUTF(Protocol.LIST_USERS);
            out.flush();
        }
    }

    public void requestMyFiles() throws Exception {
        synchronized (writeLock) {
            out.writeUTF(Protocol.LIST_MY_FILES);
            out.flush();
        }
    }

    public void sendFile(String toUser, Path file, BiConsumer<Long, Long> onProgress) throws Exception {
        long size = Files.size(file);
        if (size > Limits.MAX_FILE_BYTES) {
            throw new AppException(ErrorCode.FILE_TOO_LARGE, String.valueOf(Limits.MAX_FILE_BYTES / (1024 * 1024)));
        }

        String fileName = file.getFileName().toString();

        synchronized (writeLock) {
            out.writeUTF(Protocol.SEND_FILE);
            out.writeUTF(toUser);
            out.writeUTF(fileName);
            out.writeLong(size);
            out.flush();

            try (InputStream fileIn = Files.newInputStream(file)) {
                byte[] buffer = new byte[Limits.BUFFER_SIZE];
                long sent = 0;
                int read;
                while ((read = fileIn.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    sent += read;

                    long sentFinal = sent;
                    Platform.runLater(() -> onProgress.accept(sentFinal, size));
                }
                out.flush();
            }
        }
    }

    /**
     * Parallel download (MVP):
     * - opens a NEW socket per download
     * - performs HELLO with same username
     * - sends DOWNLOAD fileId
     * - receives FILE + bytes and writes to disk
     */
    public void startParallelDownload(String fileId, Path targetDirOrFile,
                                      BiConsumer<Long, Long> onProgress,
                                      Consumer<String> onStatus,
                                      Consumer<AppError> onFailed,
                                      Consumer<Path> onSaved) {

        Thread t = new Thread(() -> {
            Platform.runLater(() -> onStatus.accept("CONNECTING"));

            try (Socket s = new Socket(host, port);
                 DataInputStream din = new DataInputStream(new BufferedInputStream(s.getInputStream()));
                 DataOutputStream dout = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()))) {

                dout.writeUTF(Protocol.HELLO_TRANSFER);
                dout.writeUTF(username);
                dout.writeUTF(sessionToken);
                dout.flush();

                String helloResp = din.readUTF();
                if (Protocol.AUTH_FAILED.equals(helloResp) || Protocol.ERROR.equals(helloResp)) {
                    throw new AppException(AppError.read(din));
                }
                if (!Protocol.HELLO_OK.equals(helloResp)) {
                    throw new AppException(AppError.PROTOCOL_ERROR, helloResp);
                }

                dout.writeUTF(Protocol.DOWNLOAD);
                dout.writeUTF(fileId);
                dout.flush();

                String type = din.readUTF();
                if (Protocol.ERROR.equals(type)) {
                    throw new AppException(AppError.read(din));
                }
                if (!Protocol.FILE.equals(type)) {
                    throw new AppException(AppError.PROTOCOL_ERROR, type);
                }

                // Never trust the name sent by the server/sender: it could contain "..\\" or an absolute path.
                String fileName = SafeNames.sanitizeFileName(din.readUTF());
                long fileSize = din.readLong();
                if (fileSize < 0 || fileSize > Limits.MAX_FILE_BYTES) {
                    throw new AppException(AppError.PROTOCOL_ERROR, "FILE size " + fileSize);
                }

                Path finalTarget;
                OpenOption[] openOptions;
                if (Files.isDirectory(targetDirOrFile)) {
                    // Saving into a folder: stay inside it and never overwrite an existing file.
                    finalTarget = uniqueTarget(targetDirOrFile, fileName);
                    openOptions = new OpenOption[]{StandardOpenOption.CREATE_NEW};
                } else {
                    // The user explicitly picked this exact file.
                    finalTarget = targetDirOrFile;
                    openOptions = new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING};
                }

                Files.createDirectories(finalTarget.getParent());

                Platform.runLater(() -> onStatus.accept("DOWNLOADING"));

                try (OutputStream fileOut = Files.newOutputStream(finalTarget, openOptions)) {

                    byte[] buffer = new byte[Limits.BUFFER_SIZE];
                    long received = 0;

                    while (received < fileSize) {
                        int toRead = (int) Math.min(buffer.length, fileSize - received);
                        int read = din.read(buffer, 0, toRead);
                        if (read == -1) throw new EOFException("Disconnected during download");

                        fileOut.write(buffer, 0, read);
                        received += read;

                        long doneFinal = received;
                        Platform.runLater(() -> onProgress.accept(doneFinal, fileSize));
                    }
                }

                Platform.runLater(() -> {
                    onSaved.accept(finalTarget);
                    onStatus.accept("DONE");
                });

            } catch (Exception ex) {
                AppError err = AppError.from(ex);
                Platform.runLater(() -> onFailed.accept(err)); // sets status FAILED + translated reason
            }
        }, "download-" + fileId);

        t.setDaemon(true);
        t.start();
    }

    /** "name.ext", then "name (1).ext", "name (2).ext"... inside {@code dir}. */
    private static Path uniqueTarget(Path dir, String fileName) throws IOException {
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        String ext = dot > 0 ? fileName.substring(dot) : "";

        for (int i = 0; i < 1000; i++) {
            String candidate = i == 0 ? fileName : base + " (" + i + ")" + ext;
            Path p = SafeNames.resolveInside(dir, candidate);
            if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return p;
        }
        throw new IOException("Too many files named " + fileName);
    }

    public String getUsername() {
        return username;
    }

    @Override
    public void close() throws IOException {
        running = false;
        socket.close();
    }

    public void sendText(String toUser, String text) throws Exception {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) throw new AppException(ErrorCode.EMPTY_TEXT);
        if (t.length() > Limits.MAX_TEXT_CHARS) throw new AppException(ErrorCode.TEXT_TOO_LONG, String.valueOf(Limits.MAX_TEXT_CHARS));

        synchronized (writeLock) {
            out.writeUTF(Protocol.SEND_TEXT);
            out.writeUTF(toUser);
            out.writeUTF(t);
            out.flush();
        }
    }

    public record IncomingTextEvent(String messageId, String fromUser, String timestamp) {
    }
}
