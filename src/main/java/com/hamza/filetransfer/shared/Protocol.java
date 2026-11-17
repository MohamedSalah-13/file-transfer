package com.hamza.filetransfer.shared;

public final class Protocol {
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
