package com.hamza.filetransfer.client;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

/**
 * The server and user of the last successful login, so the next start fills them in.
 * Stored per OS user in {@code ~/.file-transfer/client.properties}. Never holds the password.
 */
public record LastLogin(String host, int port, String user) {

    public static final LastLogin DEFAULT = new LastLogin("127.0.0.1", 5000, "user1");

    public static Path defaultLocation() {
        return PrefsFile.defaultLocation();
    }

    /** The saved login, with {@link #DEFAULT} values for anything missing or invalid. Never throws. */
    public static LastLogin load(Path file) {
        Properties p = PrefsFile.read(file);
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

    /** Saves this login, keeping the file's other settings. */
    public void save(Path file) throws IOException {
        PrefsFile.update(file, Map.of("host", host, "port", Integer.toString(port), "user", user));
    }
}
