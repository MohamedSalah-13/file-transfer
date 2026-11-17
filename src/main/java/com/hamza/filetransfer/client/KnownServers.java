package com.hamza.filetransfer.client;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Properties;

/**
 * Servers this user decided to trust, like SSH's known_hosts: {@code host:port = SHA-256 fingerprint}.
 * Stored per OS user in {@code ~/.file-transfer/known_servers.properties}.
 */
public final class KnownServers {

    private final Path file;
    private final Properties props = new Properties();

    public KnownServers(Path file) throws IOException {
        this.file = file;
        if (Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                props.load(r);
            }
        }
    }

    public static Path defaultLocation() {
        return Path.of(System.getProperty("user.home"), ".file-transfer", "known_servers.properties");
    }

    public Path location() {
        return file;
    }

    /** The trusted fingerprint for this server, or null if it was never trusted. */
    public synchronized String fingerprint(String host, int port) {
        return props.getProperty(key(host, port));
    }

    /** Remembers (or replaces) the fingerprint for this server. */
    public synchronized void trust(String host, int port, String fingerprint) throws IOException {
        props.setProperty(key(host, port), fingerprint);
        Path parent = file.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path tmp = Files.createTempFile(parent, "known_servers", ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            props.store(w, "File Transfer: trusted server certificates (host:port = SHA-256 fingerprint)");
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static String key(String host, int port) {
        return host.trim().toLowerCase(Locale.ROOT) + ":" + port;
    }
}
