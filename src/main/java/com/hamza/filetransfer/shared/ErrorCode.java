package com.hamza.filetransfer.shared;

/**
 * Language-neutral error codes. The server sends {@code ERROR} / {@code AUTH_FAILED} as
 * {@code <code> <argCount:int> <arg...>} (all strings) and the client translates
 * {@code error.<code>} from its message bundle, filling {0}, {1}... with the args.
 * Codes are part of the wire protocol: renaming one needs the client bundles updated too.
 */
public final class ErrorCode {

    // ===== Login / handshake =====
    public static final String BAD_HANDSHAKE = "bad_handshake";
    /** args: max username length */
    public static final String INVALID_USERNAME = "invalid_username";
    /** args: min length, max length */
    public static final String WEAK_PASSWORD = "weak_password";
    /** args: lockout seconds */
    public static final String ACCOUNT_LOCKED = "account_locked";
    public static final String WRONG_CREDENTIALS = "wrong_credentials";
    public static final String INVALID_SESSION = "invalid_session";

    // ===== Commands =====
    /** args: command name */
    public static final String CONTROL_ONLY = "control_only";
    /** args: command name */
    public static final String UNKNOWN_COMMAND = "unknown_command";
    /** args: max size in MB */
    public static final String FILE_TOO_LARGE = "file_too_large";
    public static final String UNKNOWN_RECIPIENT = "unknown_recipient";
    public static final String SELF_RECIPIENT = "self_recipient";
    public static final String UNKNOWN_USER = "unknown_user";
    public static final String EMPTY_TEXT = "empty_text";
    /** args: max characters */
    public static final String TEXT_TOO_LONG = "text_too_long";
    public static final String FILE_NOT_FOUND = "file_not_found";

    private ErrorCode() {}
}
