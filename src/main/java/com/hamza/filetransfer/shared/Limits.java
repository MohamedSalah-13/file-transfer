package com.hamza.filetransfer.shared;

public final class Limits {
    public static final long MAX_FILE_BYTES = 200L * 1024 * 1024; // 200MB
    public static final int BUFFER_SIZE = 64 * 1024;
    public static final int MAX_TEXT_CHARS = 2000;

    private Limits() {}
}
