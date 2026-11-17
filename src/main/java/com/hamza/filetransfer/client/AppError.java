package com.hamza.filetransfer.client;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * A language-neutral error: a code plus string arguments, translated for display by
 * {@link I18n#error(AppError)} using the {@code error.<code>} bundle key.
 * Server codes are listed in {@link com.hamza.filetransfer.shared.ErrorCode};
 * the constants below are produced by the client itself.
 */
public record AppError(String code, List<String> args) {

    public static final String ALREADY_ONLINE = "already_online";
    public static final String CANNOT_CONNECT = "cannot_connect";
    public static final String DISCONNECTED = "disconnected";
    /** The file got shorter while it was being sent. */
    public static final String FILE_CHANGED = "file_changed";
    /** First connection to this server: the user must check and trust it. args: fingerprint */
    public static final String UNTRUSTED_SERVER = "untrusted_server";
    /** The server presents a different certificate than the trusted one. args: new fingerprint, trusted fingerprint, known-servers file */
    public static final String SERVER_CERT_CHANGED = "server_cert_changed";
    /** The TLS handshake failed (e.g. a 1.x server without TLS). args: detail */
    public static final String TLS_FAILED = "tls_failed";
    public static final String INVALID_PORT = "invalid_port";
    /** args: unexpected message type */
    public static final String PROTOCOL_ERROR = "protocol_error";
    /** args: raw exception message, for anything without a code */
    public static final String OTHER = "other";

    public AppError {
        args = List.copyOf(args);
    }

    public AppError(String code, String... args) {
        this(code, List.of(args));
    }

    /** Reads {@code <code> <argCount> <arg...>} as sent after ERROR / AUTH_FAILED. */
    static AppError read(DataInputStream in) throws IOException {
        String code = in.readUTF();
        int n = in.readInt();
        if (n < 0 || n > 16) throw new IOException("Bad error argument count: " + n);
        List<String> args = new ArrayList<>(n);
        for (int i = 0; i < n; i++) args.add(in.readUTF());
        return new AppError(code, args);
    }

    /** Best-effort mapping of any exception to a translatable error. */
    public static AppError from(Throwable t) {
        if (t instanceof AppException ae) return ae.error();
        if (t instanceof ConnectException || t instanceof UnknownHostException || t instanceof NoRouteToHostException) {
            return new AppError(CANNOT_CONNECT);
        }
        if (t instanceof EOFException || t instanceof SocketException) return new AppError(DISCONNECTED);
        String msg = t.getMessage();
        return new AppError(OTHER, msg == null || msg.isBlank() ? t.getClass().getSimpleName() : msg);
    }
}
