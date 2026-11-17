package com.hamza.filetransfer.shared;

public final class Protocol {
    // ===== Version =====
    // Every socket (CONTROL and TRANSFER) starts with: CONNECT <version:int>, then HELLO or HELLO_TRANSFER
    // right away (no reply in between). If the version differs, the server answers the login with
    // AUTH_FAILED version_mismatch <serverVersion> <clientVersion> and closes the socket.
    // The CONNECT format never changes, so any two versions can always tell each other apart.
    public static final String CONNECT = "CONNECT";
    /**
     * Wire protocol version. Bump it on ANY change to what client and server send each other
     * (a new field, a new command, a changed order); both sides must have the same value.
     * 0 means a client or server from before versioning (2.0.0 pre-release and older).
     */
    public static final int VERSION = 1;

    // ===== Handshake =====
    // CONTROL login:  HELLO <username> <password>
    //   -> HELLO_OK <canonicalUsername> <sessionToken> | AUTH_FAILED <error> | ERROR <error>
    //   A new login replaces the user's previous CONTROL connection.
    public static final String HELLO = "HELLO";
    // TRANSFER login (one extra socket per upload or download): HELLO_TRANSFER <username> <sessionToken>
    //   -> HELLO_OK | AUTH_FAILED <error>
    // Only valid while the user's CONTROL connection that issued the token is still online.
    public static final String HELLO_TRANSFER = "HELLO_TRANSFER";

    public static final String HELLO_OK = "HELLO_OK";
    /** No longer sent by the server (a new login replaces the old one); still understood by the client. */
    public static final String HELLO_TAKEN = "HELLO_TAKEN";
    public static final String AUTH_FAILED = "AUTH_FAILED";

    // ===== Client -> Server =====
    public static final String LIST_USERS = "LIST_USERS";
    // SEND_FILE <toUser> <fileName> <size:long> <bytes...> -> SEND_OK <messageId> | ERROR <error>
    // Clients send it on a TRANSFER socket (1.0.0 clients sent it on CONTROL; the server accepts both).
    public static final String SEND_FILE = "SEND_FILE";
    // SEND_TEXT <toUser> <text> -> SEND_OK <messageId> | ERROR <error>   (CONTROL)
    public static final String SEND_TEXT = "SEND_TEXT";

    public static final String LIST_MY_FILES = "LIST_MY_FILES";
    // DOWNLOAD <fileId> -> FILE <fileName> <size:long> <bytes...> | ERROR <error>   (TRANSFER)
    public static final String DOWNLOAD = "DOWNLOAD";

    public static final String LIST_THREAD = "LIST_THREAD";

    // ===== Server -> Client =====
    public static final String THREAD_MESSAGES = "THREAD_MESSAGES";
    public static final String USERS = "USERS";
    public static final String SEND_OK = "SEND_OK";
    public static final String MY_FILES = "MY_FILES";
    public static final String FILE = "FILE";

    public static final String INCOMING_FILE = "INCOMING_FILE";
    public static final String INCOMING_TEXT = "INCOMING_TEXT";

    // <error> = <code:UTF> <argCount:int> <arg:UTF>...  (codes in ErrorCode, translated by the client)
    public static final String ERROR = "ERROR";

    private Protocol() {}
}
