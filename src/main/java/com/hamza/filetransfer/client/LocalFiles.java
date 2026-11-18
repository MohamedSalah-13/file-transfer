package com.hamza.filetransfer.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

/**
 * Where a chat file is on this computer: the copy the user downloaded, or the original they
 * sent. Kept by message id in {@code ~/.file-transfer/local-files.properties}, so the Open and
 * Show-in-folder buttons (and image previews) come back after the chat reloads or the app
 * restarts. A file that was moved or deleted since is simply not found.
 */
final class LocalFiles {

    private final Path file;
    private final Properties paths; // only touched on the FX thread

    LocalFiles(Path file) {
        this.file = file;
        this.paths = PrefsFile.read(file);
    }

    static Path defaultLocation() {
        return Path.of(System.getProperty("user.home"), ".file-transfer", "local-files.properties");
    }

    /** The file for this message if it is still there, else null. */
    Path find(String messageId) {
        String p = messageId == null ? null : paths.getProperty(messageId);
        if (p == null) return null;
        Path path = Path.of(p);
        return Files.isRegularFile(path) ? path : null;
    }

    /** Remembers where this message's file is. A failure to save only loses it after a restart. */
    void remember(String messageId, Path path) {
        if (messageId == null || path == null) return;
        String value = path.toAbsolutePath().toString();
        paths.setProperty(messageId, value);
        try {
            PrefsFile.update(file, Map.of(messageId, value));
        } catch (IOException | RuntimeException e) {
            System.err.println("Could not remember where " + path.getFileName() + " is: " + e);
        }
    }
}
