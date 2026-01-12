package com.hamza.filetransfer.shared;

public final class Protocol {
    // Client -> Server
    public static final String HELLO = "HELLO";

    public static final String MODE = "MODE";
    public static final String MODE_CONTROL = "CONTROL";
    public static final String MODE_TRANSFER = "TRANSFER";
    public static final String LIST_USERS = "LIST_USERS";
    public static final String SEND_FILE = "SEND_FILE";
    public static final String SEND_TEXT = "SEND_TEXT"; // NEW

    public static final String LIST_MY_FILES = "LIST_MY_FILES";
    public static final String DOWNLOAD = "DOWNLOAD";

    public static final String LIST_THREAD = "LIST_THREAD";
    public static final String THREAD_MESSAGES = "THREAD_MESSAGES";

    public static final String HELLO_OK = "HELLO_OK";
    public static final String HELLO_TAKEN = "HELLO_TAKEN";
    public static final String USERS = "USERS";
    public static final String SEND_OK = "SEND_OK";
    public static final String MY_FILES = "MY_FILES";
    public static final String FILE = "FILE";

    public static final String INCOMING_FILE = "INCOMING_FILE";
    public static final String INCOMING_TEXT = "INCOMING_TEXT"; // NEW

    public static final String ERROR = "ERROR";

    private Protocol() {}
}
