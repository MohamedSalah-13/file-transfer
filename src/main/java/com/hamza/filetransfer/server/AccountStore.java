package com.hamza.filetransfer.server;

import com.hamza.filetransfer.shared.SafeNames;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Username/password accounts, stored as salted PBKDF2 hashes in a properties file.
 * The first successful login with a new username registers it (good enough for a LAN app);
 * after that, the same password is required.
 */
public class AccountStore {

    public static final int MIN_PASSWORD_LENGTH = 6;
    public static final int MAX_PASSWORD_LENGTH = 128;

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 210_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;

    private static final int MAX_FAILURES = 5;
    private static final long LOCKOUT_MILLIS = 30_000;

    public enum Status { OK, REGISTERED, WRONG_PASSWORD, WEAK_PASSWORD, LOCKED }

    public record Result(Status status, String username) {
        public boolean success() { return status == Status.OK || status == Status.REGISTERED; }
    }

    private record Account(String displayName, int iterations, byte[] salt, byte[] hash) {}

    private record Failures(int count, long lockedUntil) {}

    private final Path file;
    private final Properties props = new Properties();
    private final Map<String, Failures> failures = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public AccountStore(Path file) throws IOException {
        this.file = file;
        if (Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                props.load(r);
            }
        }
    }

    /** Is there an account with this name (case-insensitive)? */
    public synchronized boolean exists(String username) {
        return props.containsKey(SafeNames.accountKey(username));
    }

    /**
     * Checks the password, or registers the account if the name is new.
     * The returned username is the canonical spelling stored for the account.
     */
    public Result authenticate(String username, char[] password) throws IOException {
        String key = SafeNames.accountKey(username);

        Failures f = failures.get(key);
        if (f != null && f.lockedUntil() > System.currentTimeMillis()) {
            return new Result(Status.LOCKED, null);
        }

        Account existing = load(key);
        if (existing == null) {
            if (password.length < MIN_PASSWORD_LENGTH || password.length > MAX_PASSWORD_LENGTH) {
                return new Result(Status.WEAK_PASSWORD, null);
            }
            byte[] salt = new byte[SALT_BYTES];
            random.nextBytes(salt);
            Account created = new Account(username, ITERATIONS, salt, hash(password, salt, ITERATIONS));

            Account winner = storeIfAbsent(key, created);
            if (winner == created) return new Result(Status.REGISTERED, username);
            existing = winner; // someone registered the same name at the same moment
        }

        boolean ok = password.length <= MAX_PASSWORD_LENGTH
                && MessageDigest.isEqual(existing.hash(), hash(password, existing.salt(), existing.iterations()));

        if (ok) {
            failures.remove(key);
            return new Result(Status.OK, existing.displayName());
        }

        failures.compute(key, (k, old) -> {
            int count = (old == null ? 0 : old.count()) + 1;
            return count >= MAX_FAILURES
                    ? new Failures(0, System.currentTimeMillis() + LOCKOUT_MILLIS)
                    : new Failures(count, 0);
        });
        return new Result(Status.WRONG_PASSWORD, null);
    }

    private synchronized Account load(String key) {
        String v = props.getProperty(key);
        if (v == null) return null;
        // iterations:salt:hash:displayName  (display names cannot contain ':')
        String[] parts = v.split(":", 4);
        if (parts.length != 4) throw new IllegalStateException("Corrupt account record for " + key);
        Base64.Decoder b64 = Base64.getDecoder();
        return new Account(parts[3], Integer.parseInt(parts[0]), b64.decode(parts[1]), b64.decode(parts[2]));
    }

    private synchronized Account storeIfAbsent(String key, Account account) throws IOException {
        Account existing = load(key);
        if (existing != null) return existing;

        Base64.Encoder b64 = Base64.getEncoder();
        props.setProperty(key, account.iterations() + ":" + b64.encodeToString(account.salt()) + ":"
                + b64.encodeToString(account.hash()) + ":" + account.displayName());
        save();
        return account;
    }

    private void save() throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path tmp = Files.createTempFile(parent, "accounts", ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            props.store(w, "File Transfer accounts (PBKDF2 hashes) - do not edit by hand");
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static byte[] hash(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 not available", e);
        } finally {
            spec.clearPassword();
        }
    }
}
