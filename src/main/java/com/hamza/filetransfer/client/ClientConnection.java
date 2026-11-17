package com.hamza.filetransfer.client;

import com.hamza.filetransfer.shared.ErrorCode;
import com.hamza.filetransfer.shared.Limits;
import com.hamza.filetransfer.shared.Protocol;
import com.hamza.filetransfer.shared.SafeNames;
import javafx.application.Platform;

import com.hamza.filetransfer.shared.Tls;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
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

    /** (peer the thread belongs to, its messages). Replies can arrive after the user switched chats. */
    private BiConsumer<String, List<ChatMessage>> onThreadMessages = (peer, msgs) -> {
    };

    /**
     * Small CONTROL requests (list users, list thread...) are written from this thread, never from the
     * caller's: a socket write can block (slow network, full buffers) and the FX thread must not wait on it.
     */
    private final ExecutorService requests = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "client-requests");
        t.setDaemon(true);
        return t;
    });

    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    /** Covers the TLS handshake and the login reply; a 1.x server without TLS never answers the handshake. */
    private static final int HANDSHAKE_TIMEOUT_MILLIS = 10_000;

    private final KnownServers knownServers;
    private final SSLSocketFactory tlsSockets;

    private Consumer<IncomingTextEvent> onIncomingText = evt -> {
    };

    /**
     * Opens the TLS CONTROL connection and logs in. A new username is registered with this password
     * on first login; afterwards the same password is required.
     * The server must present the certificate trusted in {@code knownServers} for {@code host:port};
     * otherwise this throws an {@link AppException} with {@link AppError#UNTRUSTED_SERVER} (args: fingerprint)
     * or {@link AppError#SERVER_CERT_CHANGED}, and the password is never sent.
     * Blocks on the network: call it off the FX thread. Set the callbacks, then call {@link #start()}.
     */
    public ClientConnection(String host, int port, String username, char[] password,
                            KnownServers knownServers) throws Exception {
        this.host = host;
        this.port = port;
        this.knownServers = knownServers;

        SSLContext tls = SSLContext.getInstance(Tls.PROTOCOL);
        tls.init(null, new TrustManager[]{new TofuTrustManager(knownServers, host, port)}, null);
        this.tlsSockets = tls.getSocketFactory();

        Socket raw = new Socket();
        try {
            raw.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS);
            this.socket = secure(raw);
            this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

            // CONNECT <version>, HELLO <username> <password>
            synchronized (writeLock) {
                writeVersion(out);
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
                throw new AppException(loginError(AppError.read(in)));
            }
            if (!Protocol.HELLO_OK.equals(resp)) {
                throw new AppException(AppError.PROTOCOL_ERROR, resp);
            }
            this.username = in.readUTF();
            this.sessionToken = in.readUTF();
            socket.setSoTimeout(0); // from now on the listener waits for pushes indefinitely
        } catch (Exception e) {
            raw.close(); // also closes the TLS socket layered on it
            requests.shutdownNow();
            throw e;
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    /** First thing on every socket, before HELLO / HELLO_TRANSFER (see {@link Protocol#CONNECT}). */
    private static void writeVersion(DataOutputStream out) throws IOException {
        out.writeUTF(Protocol.CONNECT);
        out.writeInt(Protocol.VERSION);
    }

    /**
     * A server from before versioning doesn't know CONNECT and answers bad_handshake:
     * report that as a version mismatch (server version 0) so the user knows what to do.
     */
    private static AppError loginError(AppError e) {
        if (ErrorCode.BAD_HANDSHAKE.equals(e.code())) {
            return new AppError(ErrorCode.VERSION_MISMATCH, "0", String.valueOf(Protocol.VERSION));
        }
        return e;
    }

    /**
     * Runs the TLS handshake on a connected socket. The read timeout stays at
     * {@link #HANDSHAKE_TIMEOUT_MILLIS}; callers reset it once the login reply has arrived.
     */
    private SSLSocket secure(Socket raw) throws Exception {
        SSLSocket s = (SSLSocket) tlsSockets.createSocket(raw, host, port, true);
        Tls.restrict(s);
        s.setSoTimeout(HANDSHAKE_TIMEOUT_MILLIS);
        try {
            s.startHandshake();
        } catch (SSLException | SocketTimeoutException e) {
            for (Throwable c = e; c != null; c = c.getCause()) {
                if (c instanceof TofuTrustManager.UntrustedServerException u) {
                    if (u.trustedFingerprint() == null) {
                        throw new AppException(AppError.UNTRUSTED_SERVER, u.fingerprint());
                    }
                    throw new AppException(AppError.SERVER_CERT_CHANGED,
                            u.fingerprint(), u.trustedFingerprint(), knownServers.location().toString());
                }
            }
            // Typically a 1.x server without TLS, or something else listening on that port.
            throw new AppException(AppError.TLS_FAILED, String.valueOf(e.getMessage()));
        }
        return s;
    }

    /** Starts reading server messages. Call once, after the callbacks are set, so no push is lost. */
    public void start() {
        Thread listener = new Thread(this::listenLoop, "client-control-listener");
        listener.setDaemon(true);
        listener.start();
    }

    public void setOnIncomingText(Consumer<IncomingTextEvent> onIncomingText) {
        this.onIncomingText = onIncomingText;
    }

    public void setOnThreadMessages(BiConsumer<String, List<ChatMessage>> onThreadMessages) {
        this.onThreadMessages = onThreadMessages;
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

                    Platform.runLater(() -> onThreadMessages.accept(peer, msgs));

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

    /** Asks for the chat history with {@code peer}; the reply goes to the thread-messages callback. */
    public void requestThread(String peer) {
        sendRequest(o -> {
            o.writeUTF(Protocol.LIST_THREAD);
            o.writeUTF(peer);
        });
    }

    @FunctionalInterface
    private interface RequestWriter {
        void write(DataOutputStream out) throws IOException;
    }

    /** Queues a small request; it is written on the requests thread, after any upload in progress. */
    private void sendRequest(RequestWriter writer) {
        try {
            requests.execute(() -> {
                try {
                    synchronized (writeLock) {
                        writer.write(out);
                        out.flush();
                    }
                } catch (IOException e) {
                    if (running) Platform.runLater(() -> onError.accept(AppError.from(e)));
                }
            });
        } catch (RejectedExecutionException ignored) {
            // connection closed
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

    public void requestUsers() {
        sendRequest(o -> o.writeUTF(Protocol.LIST_USERS));
    }

    public void requestMyFiles() {
        sendRequest(o -> o.writeUTF(Protocol.LIST_MY_FILES));
    }

    public void sendFile(String toUser, Path file, BiConsumer<Long, Long> onProgress) throws Exception {
        long size = Files.size(file);
        if (size > Limits.MAX_FILE_BYTES) {
            throw new AppException(ErrorCode.FILE_TOO_LARGE, String.valueOf(Limits.MAX_FILE_BYTES / (1024 * 1024)));
        }

        String fileName = file.getFileName().toString();

        // Each upload gets its own TRANSFER socket, so the CONTROL socket stays free for chat,
        // notifications and other requests, and several uploads can run at once.
        // Open the file first, so a missing file fails before anything is sent.
        try (InputStream fileIn = Files.newInputStream(file);
             TransferSocket t = openTransfer()) {
            t.out.writeUTF(Protocol.SEND_FILE);
            t.out.writeUTF(toUser);
            t.out.writeUTF(fileName);
            t.out.writeLong(size);

            // The server reads exactly `size` bytes, so send exactly that many even if the file grows.
            // If it shrinks, closing this socket (try-with-resources) makes the server discard the upload.
            byte[] buffer = new byte[Limits.BUFFER_SIZE];
            long sent = 0;
            while (sent < size) {
                int read = fileIn.read(buffer, 0, (int) Math.min(buffer.length, size - sent));
                if (read == -1) throw new AppException(AppError.FILE_CHANGED);
                t.out.write(buffer, 0, read);
                sent += read;

                long sentFinal = sent;
                Platform.runLater(() -> onProgress.accept(sentFinal, size));
            }
            t.out.flush();

            // The reply comes on this socket: SEND_OK <messageId> or ERROR <error> (e.g. unknown recipient).
            String reply = t.in.readUTF();
            if (Protocol.ERROR.equals(reply)) throw new AppException(AppError.read(t.in));
            if (!Protocol.SEND_OK.equals(reply)) throw new AppException(AppError.PROTOCOL_ERROR, reply);
            t.in.readUTF(); // message id
        }
    }

    /** An extra socket authenticated with this session's token, used for one upload or download. */
    private static final class TransferSocket implements Closeable {
        final Socket socket;
        final DataInputStream in;
        final DataOutputStream out;

        TransferSocket(Socket socket) throws IOException {
            this.socket = socket;
            this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    /** Opens a TRANSFER socket: {@code HELLO_TRANSFER <user> <token>} -> {@code HELLO_OK}. */
    private TransferSocket openTransfer() throws Exception {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS);
            SSLSocket secured = secure(s); // same pinned certificate as the CONTROL connection
            TransferSocket t = new TransferSocket(secured);
            writeVersion(t.out);
            t.out.writeUTF(Protocol.HELLO_TRANSFER);
            t.out.writeUTF(username);
            t.out.writeUTF(sessionToken);
            t.out.flush();

            String reply = t.in.readUTF();
            if (Protocol.AUTH_FAILED.equals(reply) || Protocol.ERROR.equals(reply)) {
                throw new AppException(loginError(AppError.read(t.in)));
            }
            if (!Protocol.HELLO_OK.equals(reply)) throw new AppException(AppError.PROTOCOL_ERROR, reply);
            secured.setSoTimeout(0); // large transfers may pause; no read timeout from here on
            return t;
        } catch (Exception e) {
            s.close();
            throw e;
        }
    }

    /**
     * Downloads on its own TRANSFER socket (in parallel with chat and other transfers):
     * sends DOWNLOAD fileId, receives FILE + bytes and writes them to disk.
     */
    public void startParallelDownload(String fileId, Path targetDirOrFile,
                                      BiConsumer<Long, Long> onProgress,
                                      Consumer<String> onStatus,
                                      Consumer<AppError> onFailed,
                                      Consumer<Path> onSaved) {

        Thread t = new Thread(() -> {
            Platform.runLater(() -> onStatus.accept("CONNECTING"));

            try (TransferSocket ts = openTransfer()) {
                DataInputStream din = ts.in;
                DataOutputStream dout = ts.out;

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
                } catch (Exception e) {
                    // Don't leave a truncated file that looks like the real one.
                    try {
                        Files.deleteIfExists(finalTarget);
                    } catch (IOException ignored) {
                    }
                    throw e;
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
        requests.shutdownNow();
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
