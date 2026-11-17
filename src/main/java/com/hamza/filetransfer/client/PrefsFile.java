package com.hamza.filetransfer.client;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Properties;

/**
 * The client's settings file ({@code ~/.file-transfer/client.properties}), shared by
 * {@link LastLogin} and {@link Appearance}. Each update re-reads the file and changes only its
 * own keys, so saving one setting never drops another (or another window's).
 */
final class PrefsFile {

    private static final Object LOCK = new Object();

    private PrefsFile() {}

    static Path defaultLocation() {
        return Path.of(System.getProperty("user.home"), ".file-transfer", "client.properties");
    }

    /** The saved values; empty for a missing or broken file. Never throws. */
    static Properties read(Path file) {
        try {
            return readStrict(file);
        } catch (IOException | IllegalArgumentException e) {
            return new Properties();
        }
    }

    /** Sets these keys, keeps the others, and writes the file atomically. */
    static void update(Path file, Map<String, String> values) throws IOException {
        synchronized (LOCK) {
            Properties p;
            try {
                p = readStrict(file);
            } catch (IllegalArgumentException e) {
                p = new Properties(); // a broken file: replace it
            }
            values.forEach(p::setProperty);
            Path parent = file.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            Path tmp = Files.createTempFile(parent, "client", ".tmp");
            try {
                try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                    p.store(w, "File Transfer client settings (no password)");
                }
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(tmp);
            }
        }
    }

    /**
     * Missing file means empty; any other read error is thrown, so an update never overwrites
     * a file it couldn't read.
     */
    private static Properties readStrict(Path file) throws IOException {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (NoSuchFileException e) {
            // first start
        }
        return p;
    }
}
