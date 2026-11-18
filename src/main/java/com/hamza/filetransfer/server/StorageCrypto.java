package com.hamza.filetransfer.server;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Encryption at rest for what the server stores under {@code users/}: file contents ({@code .bin}) and
 * message details ({@code .meta}). The master key is a random AES-256 key in {@code keys/storage.key},
 * created on first start. Losing that file loses every stored file and message.
 *
 * <p>Format ("FTE1"): {@code "FTE1" | salt (16 bytes) | segment...}. Each file gets its own key,
 * HMAC-SHA256(master, label || salt), so AES-GCM nonces can simply count segments and never repeat
 * across files. The plaintext is cut into {@value #SEGMENT}-byte segments (the last may be shorter or
 * empty), each sealed with AES-256-GCM: nonce = segment number, AAD = header || context || segment
 * number || last flag. So downloads stream without holding a whole file in memory, every segment is
 * verified before it is used, a cut-off or reordered file fails, and the context (the file's path
 * under {@code users/}) makes a file copied or swapped into another place fail too.
 */
public final class StorageCrypto {

    public static final int SEGMENT = 64 * 1024;
    static final int TAG = 16;
    private static final byte[] MAGIC = "FTE1".getBytes(StandardCharsets.US_ASCII);
    private static final int SALT = 16;
    static final int HEADER = MAGIC.length + SALT;
    private static final int KEY_BYTES = 32;
    private static final byte[] KDF_LABEL = "file-transfer storage v1".getBytes(StandardCharsets.US_ASCII);
    private static final String KEY_FILE = "storage.key";

    private final byte[] master;
    private final Path keyFile; // null for keys made in tests
    private final SecureRandom random = new SecureRandom();

    private StorageCrypto(byte[] master, Path keyFile) {
        if (master.length != KEY_BYTES) throw new IllegalArgumentException("storage key must be 32 bytes");
        this.master = master.clone();
        this.keyFile = keyFile;
    }

    /** Uses this key; for tests. */
    static StorageCrypto ofKey(byte[] master) {
        return new StorageCrypto(master, null);
    }

    /** Reads {@code <dir>/storage.key}, creating a new random key first if there is none. */
    public static StorageCrypto loadOrCreate(Path dir) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve(KEY_FILE);
        if (!Files.exists(file)) {
            byte[] key = new byte[KEY_BYTES];
            new SecureRandom().nextBytes(key);
            Path tmp = Files.createTempFile(dir, "storage", ".tmp");
            Files.writeString(tmp, "# File Transfer storage key. Back it up: without it the stored files and messages can't be read.\n"
                    + Base64.getEncoder().encodeToString(key) + "\n", StandardCharsets.UTF_8);
            try {
                // ATOMIC_MOVE without REPLACE_EXISTING: if another start just made a key, keep that one.
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
            } catch (FileAlreadyExistsException e) {
                Files.deleteIfExists(tmp);
            }
        }
        String encoded = Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                .map(String::trim).filter(l -> !l.isEmpty() && !l.startsWith("#"))
                .findFirst().orElseThrow(() -> new IOException("No key in " + file));
        byte[] key;
        try {
            key = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            throw new IOException("Broken key in " + file, e);
        }
        if (key.length != KEY_BYTES) throw new IOException("Broken key in " + file + ": expected 32 bytes");
        return new StorageCrypto(key, file);
    }

    /** Where the key is kept (shown in the server window so it gets backed up); null for test keys. */
    public Path keyFile() {
        return keyFile;
    }

    // ===== whole files (small: .meta) =====

    public byte[] encrypt(byte[] plain, String context) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(plain.length + HEADER + TAG * 2);
        try (OutputStream out = encrypt(bytes, context)) {
            out.write(plain);
        }
        return bytes.toByteArray();
    }

    public byte[] decrypt(byte[] sealed, String context) throws IOException {
        try (InputStream in = decrypt(new ByteArrayInputStream(sealed), context)) {
            return in.readAllBytes();
        }
    }

    // ===== streams (large: .bin) =====

    /** Writes the header now and seals segments as they fill; closing seals the last one and closes {@code out}. */
    public OutputStream encrypt(OutputStream out, String context) throws IOException {
        byte[] header = new byte[HEADER];
        System.arraycopy(MAGIC, 0, header, 0, MAGIC.length);
        byte[] salt = new byte[SALT];
        random.nextBytes(salt);
        System.arraycopy(salt, 0, header, MAGIC.length, SALT);
        out.write(header);
        return new Sealing(out, fileKey(salt), header, context);
    }

    /** Reads the header now; every segment is verified before its bytes are returned. */
    public InputStream decrypt(InputStream in, String context) throws IOException {
        byte[] header = in.readNBytes(HEADER);
        if (header.length < HEADER || !Arrays.equals(Arrays.copyOf(header, MAGIC.length), MAGIC)) {
            throw new IOException("Not an encrypted storage file");
        }
        byte[] salt = Arrays.copyOfRange(header, MAGIC.length, HEADER);
        return new Opening(new PushbackInputStream(in, 1), fileKey(salt), header, context);
    }

    /** The plaintext size of an encrypted file of {@code sealedSize} bytes, or -1 if no FTE1 file has that size. */
    public static long plainSize(long sealedSize) {
        long body = sealedSize - HEADER;
        if (body < TAG) return -1;
        long segments = (body + SEGMENT + TAG - 1) / (SEGMENT + TAG);
        long plain = body - segments * TAG;
        return plain < 0 ? -1 : plain;
    }

    /** True if the file starts like an FTE1 file. Plain .meta files start with '#', so they never do. */
    public static boolean looksEncrypted(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return Arrays.equals(in.readNBytes(MAGIC.length), MAGIC);
        }
    }

    private SecretKeySpec fileKey(byte[] salt) throws IOException {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(master, "HmacSHA256"));
            mac.update(KDF_LABEL);
            return new SecretKeySpec(mac.doFinal(salt), "AES");
        } catch (GeneralSecurityException e) {
            throw new IOException("HMAC-SHA256 not available", e);
        }
    }

    private static Cipher segmentCipher(int mode, SecretKeySpec key, byte[] header, byte[] context,
                                        long index, boolean last) throws GeneralSecurityException {
        byte[] nonce = ByteBuffer.allocate(12).putInt(0).putLong(index).array();
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(mode, key, new GCMParameterSpec(TAG * 8, nonce));
        c.updateAAD(header);
        c.updateAAD(context);
        c.updateAAD(ByteBuffer.allocate(9).putLong(index).put((byte) (last ? 1 : 0)).array());
        return c;
    }

    private static final class Sealing extends FilterOutputStream {
        private final SecretKeySpec key;
        private final byte[] header;
        private final byte[] context;
        private final byte[] buf = new byte[SEGMENT];
        private int filled;
        private long index;
        private boolean closed;

        Sealing(OutputStream out, SecretKeySpec key, byte[] header, String context) {
            super(out);
            this.key = key;
            this.header = header;
            this.context = context.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            while (len > 0) {
                // A full segment is sealed only once more data arrives, so the last one is always known.
                if (filled == SEGMENT) seal(false);
                int n = Math.min(len, SEGMENT - filled);
                System.arraycopy(b, off, buf, filled, n);
                filled += n;
                off += n;
                len -= n;
            }
        }

        private void seal(boolean last) throws IOException {
            try {
                out.write(segmentCipher(Cipher.ENCRYPT_MODE, key, header, context, index++, last).doFinal(buf, 0, filled));
            } catch (GeneralSecurityException e) {
                throw new IOException("Encryption failed", e);
            }
            filled = 0;
        }

        @Override
        public void flush() throws IOException {
            out.flush(); // segments are only sealed when full or on close
        }

        @Override
        public void close() throws IOException {
            if (closed) return;
            closed = true;
            try {
                seal(true);
                out.flush();
            } finally {
                out.close();
            }
        }
    }

    private static final class Opening extends InputStream {
        private final PushbackInputStream in;
        private final SecretKeySpec key;
        private final byte[] header;
        private final byte[] context;
        private byte[] plain = new byte[0];
        private int pos;
        private long index;
        private boolean done;

        Opening(PushbackInputStream in, SecretKeySpec key, byte[] header, String context) {
            this.in = in;
            this.key = key;
            this.header = header;
            this.context = context.getBytes(StandardCharsets.UTF_8);
        }

        /** Decrypts the next segment; false at the end of the file. */
        private boolean next() throws IOException {
            if (done) return false;
            byte[] sealed = in.readNBytes(SEGMENT + TAG);
            boolean last;
            if (sealed.length < SEGMENT + TAG) {
                last = true;
            } else {
                int peek = in.read();
                last = peek == -1;
                if (!last) in.unread(peek);
            }
            try {
                plain = segmentCipher(Cipher.DECRYPT_MODE, key, header, context, index++, last).doFinal(sealed);
            } catch (AEADBadTagException e) {
                throw new IOException("Stored file failed its integrity check (damaged, cut off, or moved from another place)", e);
            } catch (GeneralSecurityException e) {
                throw new IOException("Decryption failed", e);
            }
            pos = 0;
            done = last;
            return true;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) == -1 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            while (pos == plain.length) {
                if (!next()) return -1;
            }
            int n = Math.min(len, plain.length - pos);
            System.arraycopy(plain, pos, b, off, n);
            pos += n;
            return n;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
