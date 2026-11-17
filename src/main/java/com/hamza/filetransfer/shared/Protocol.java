package com.hamza.filetransfer.shared;

public final class Protocol {
    // ===== Handshake =====
    // CONTROL login:  HELLO <username> <password>
    //   -> HELLO_OK <canonicalUsername> <sessionToken> | AUTH_FAILED <reason> | HELLO_TAKEN | ERROR <msg>
    public static final String HELLO = "HELLO";
    // TRANSFER login (extra parallel sockets): HELLO_TRANSFER <username> <sessionToken>
    //   -> HELLO_OK | AUTH_FAILED <reason>
    // Only valid while the user's CONTROL connection that issued the token is still online.
    public static final String HELLO_TRANSFER = "HELLO_TRANSFER";

    public static final String HELLO_OK = "HELLO_OK";
    public static final String HELLO_TAKEN = "HELLO_TAKEN";
    public static final String AUTH_FAILED = "AUTH_FAILED";

    // ===== Client -> Server =====
    public static final String LIST_USERS = "LIST_USERS";
    public static final String SEND_FILE = "SEND_FILE";
    public static final String SEND_TEXT = "SEND_TEXT";

    public static final String LIST_MY_FILES = "LIST_MY_FILES";
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

    public static final String ERROR = "ERROR";

    private Protocol() {}
}
