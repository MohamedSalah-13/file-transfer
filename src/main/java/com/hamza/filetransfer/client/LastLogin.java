package com.hamza.filetransfer.client;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/**
 * The server and user of the last successful login, so the next start fills them in.
 * Stored per OS user in {@code ~/.file-transfer/client.properties}. Never holds the password.
 */
public record LastLogin(String host, int port, String user) {

    public static final LastLogin DEFAULT = new LastLogin("127.0.0.1", 5000, "user1");

    public static Path defaultLocation() {
        return Path.of(System.getProperty("user.home"), ".file-transfer", "client.properties");
    }

    /** The saved login, or {@link #DEFAULT} for anything missing or unreadable. Never throws. */
    public static LastLogin load(Path file) {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException | IllegalArgumentException e) {
            return DEFAULT; // first start, or a broken file: just use the defaults
        }
        String host = p.getProperty("host", "").trim();
        String user = p.getProperty("user", "").trim();
        int port;
        try {
            port = Integer.parseInt(p.getProperty("port", "").trim());
        } catch (NumberFormatException e) {
            port = DEFAULT.port;
        }
        if (port < 1 || port > 65535) port = DEFAULT.port;
        return new LastLogin(host.isEmpty() ? DEFAULT.host : host, port, user.isEmpty() ? DEFAULT.user : user);
    }

    /** Writes this login atomically, so a crash never leaves a half-written file. */
    public void save(Path file) throws IOException {
        Properties p = new Properties();
        p.setProperty("host", host);
        p.setProperty("port", Integer.toString(port));
        p.setProperty("user", user);
        Path parent = file.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path tmp = Files.createTempFile(parent, "client", ".tmp");
        try {
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                p.store(w, "File Transfer: last successful login (no password)");
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
