package com.hamza.filetransfer.shared;

import java.nio.file.Path;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Validation and sanitizing for every untrusted name that ends up in a file-system path
 * (usernames, message ids, file names). Used by both the server and the client.
 */
public final class SafeNames {

    public static final int MAX_USERNAME_LENGTH = 32;
    public static final int MAX_FILE_NAME_LENGTH = 180;

    // Letters (any language, e.g. Arabic), digits, '_' and '-'. No dots, slashes or spaces,
    // so a username can never be "..", an absolute path, or contain a separator.
    private static final Pattern USERNAME = Pattern.compile("^[\\p{L}\\p{N}_-]{1," + MAX_USERNAME_LENGTH + "}$");

    // Characters that are illegal or dangerous in Windows/Unix file names, plus control chars.
    private static final Pattern BAD_FILE_CHARS = Pattern.compile("[\\\\/:*?\"<>|\\p{Cntrl}]");

    private static final Set<String> WINDOWS_RESERVED = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private SafeNames() {}

    /** Unicode-normalizes and trims a username. Returns null for null input. */
    public static String normalizeUsername(String raw) {
        if (raw == null) return null;
        return Normalizer.normalize(raw.trim(), Normalizer.Form.NFC);
    }

    /** True when the (already normalized) username is safe to use as a directory name. */
    public static boolean isValidUsername(String username) {
        if (username == null || !USERNAME.matcher(username).matches()) return false;
        return !WINDOWS_RESERVED.contains(username.toUpperCase(Locale.ROOT));
    }

    /** Case-insensitive key, so "Ali" and "ali" are the same account (Windows paths are case-insensitive). */
    public static String accountKey(String username) {
        return username.toLowerCase(Locale.ROOT);
    }

    /** Message / file ids are server-generated UUIDs; anything else is rejected. */
    public static boolean isValidMessageId(String id) {
        if (id == null || id.length() != 36) return false;
        try {
            return UUID.fromString(id).toString().equals(id.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Turns an untrusted file name into a single, harmless path segment:
     * strips any directory part, illegal characters, leading dots, trailing dots/spaces,
     * and Windows reserved device names.
     */
    public static String sanitizeFileName(String raw) {
        if (raw == null) return "file";

        // Keep only the last segment, whatever separator was used.
        String name = raw;
        int cut = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (cut >= 0) name = name.substring(cut + 1);

        name = Normalizer.normalize(name, Normalizer.Form.NFC);
        name = BAD_FILE_CHARS.matcher(name).replaceAll("_").strip();

        // No hidden files / ".." and no trailing dots or spaces (Windows strips them silently).
        name = name.replaceAll("^\\.+", "").replaceAll("[. ]+$", "");

        if (name.length() > MAX_FILE_NAME_LENGTH) {
            int dot = name.lastIndexOf('.');
            String ext = (dot > 0 && name.length() - dot <= 16) ? name.substring(dot) : "";
            name = name.substring(0, MAX_FILE_NAME_LENGTH - ext.length()) + ext;
        }

        if (name.isEmpty()) return "file";

        int dot = name.indexOf('.');
        String base = dot >= 0 ? name.substring(0, dot) : name;
        if (WINDOWS_RESERVED.contains(base.toUpperCase(Locale.ROOT))) name = "_" + name;

        return name;
    }

    /**
     * Resolves {@code child} under {@code base} and guarantees the result stays inside {@code base}.
     *
     * @throws SecurityException if the resolved path escapes the base directory
     */
    public static Path resolveInside(Path base, String child) {
        Path root = base.toAbsolutePath().normalize();
        Path resolved = root.resolve(child).normalize();
        if (!resolved.startsWith(root) || resolved.equals(root)) {
            throw new SecurityException("Path escapes base directory: " + child);
        }
        return resolved;
    }
}
