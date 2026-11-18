package com.hamza.filetransfer.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;

import static com.hamza.filetransfer.server.StorageCrypto.SEGMENT;
import static org.junit.jupiter.api.Assertions.*;

class StorageCryptoTest {

    @TempDir
    Path dir;

    private final StorageCrypto crypto = StorageCrypto.ofKey(new byte[32]);

    private static byte[] data(int size) {
        byte[] b = new byte[size];
        new Random(size).nextBytes(b);
        return b;
    }

    @Test
    void roundTripsEverySizeAroundSegmentBoundaries() throws Exception {
        for (int size : new int[]{0, 1, 100, SEGMENT - 1, SEGMENT, SEGMENT + 1, 2 * SEGMENT, 3 * SEGMENT + 17}) {
            byte[] plain = data(size);
            byte[] sealed = crypto.encrypt(plain, "ali/inbox/x.bin");
            assertArrayEquals(plain, crypto.decrypt(sealed, "ali/inbox/x.bin"), "size " + size);
            assertEquals(size, StorageCrypto.plainSize(sealed.length), "plain size from sealed size, " + size);
        }
    }

    @Test
    void streamsDecryptWithSmallReads() throws Exception {
        byte[] plain = data(2 * SEGMENT + 500);
        byte[] sealed = crypto.encrypt(plain, "c");
        try (InputStream in = crypto.decrypt(new ByteArrayInputStream(sealed), "c")) {
            byte[] back = new byte[plain.length];
            int pos = 0;
            int b;
            while ((b = in.read()) != -1) back[pos++] = (byte) b;
            assertEquals(plain.length, pos);
            assertArrayEquals(plain, back);
        }
    }

    @Test
    void theSameContentEncryptsDifferentlyEachTime() throws Exception {
        byte[] plain = data(1000);
        assertFalse(Arrays.equals(crypto.encrypt(plain, "c"), crypto.encrypt(plain, "c")));
    }

    @Test
    void aFileFromAnotherPlaceDoesNotDecrypt() throws Exception {
        byte[] sealed = crypto.encrypt(data(1000), "ali/inbox/x.bin");
        assertThrows(IOException.class, () -> crypto.decrypt(sealed, "carol/inbox/y.bin"));
    }

    @Test
    void anyChangedByteIsDetected() throws Exception {
        byte[] sealed = crypto.encrypt(data(3000), "c");
        for (int i : new int[]{0, 5, StorageCrypto.HEADER, sealed.length / 2, sealed.length - 1}) {
            byte[] bad = sealed.clone();
            bad[i] ^= 1;
            assertThrows(IOException.class, () -> crypto.decrypt(bad, "c"), "byte " + i);
        }
    }

    @Test
    void cuttingOffWholeSegmentsIsDetected() throws Exception {
        byte[] sealed = crypto.encrypt(data(3 * SEGMENT), "c");
        // Drop the last segment exactly: the one before it was not sealed as the last one.
        byte[] cut = Arrays.copyOf(sealed, StorageCrypto.HEADER + 2 * (SEGMENT + StorageCrypto.TAG));
        assertThrows(IOException.class, () -> crypto.decrypt(cut, "c"));
        // Appending a copy of a segment is caught too.
        byte[] longer = Arrays.copyOf(sealed, sealed.length + SEGMENT + StorageCrypto.TAG);
        System.arraycopy(sealed, StorageCrypto.HEADER, longer, sealed.length, SEGMENT + StorageCrypto.TAG);
        assertThrows(IOException.class, () -> crypto.decrypt(longer, "c"));
    }

    @Test
    void anotherKeyCannotDecrypt() throws Exception {
        byte[] other = new byte[32];
        other[0] = 1;
        byte[] sealed = crypto.encrypt(data(100), "c");
        assertThrows(IOException.class, () -> StorageCrypto.ofKey(other).decrypt(sealed, "c"));
    }

    @Test
    void theKeyFileIsCreatedOnceAndReused() throws Exception {
        StorageCrypto first = StorageCrypto.loadOrCreate(dir.resolve("keys"));
        assertTrue(Files.exists(dir.resolve("keys/storage.key")));
        byte[] sealed = first.encrypt(data(500), "c");

        StorageCrypto again = StorageCrypto.loadOrCreate(dir.resolve("keys"));
        assertArrayEquals(data(500), again.decrypt(sealed, "c"), "a restart reads the same key");
    }

    @Test
    void aBrokenKeyFileIsReportedNotReplaced() throws Exception {
        Path keys = Files.createDirectories(dir.resolve("keys"));
        Files.writeString(keys.resolve("storage.key"), "not base64 at all!");
        assertThrows(IOException.class, () -> StorageCrypto.loadOrCreate(keys));
        assertEquals("not base64 at all!", Files.readString(keys.resolve("storage.key")), "never overwritten");
    }

    @Test
    void plainFilesAreRecognised() throws Exception {
        Path plainMeta = dir.resolve("a.meta");
        Files.writeString(plainMeta, "#Tue Nov 17\nmessageId=x\n");
        Path sealed = dir.resolve("b.meta");
        Files.write(sealed, crypto.encrypt(data(10), "c"));
        assertFalse(StorageCrypto.looksEncrypted(plainMeta));
        assertTrue(StorageCrypto.looksEncrypted(sealed));
        assertEquals(-1, StorageCrypto.plainSize(5), "too small to be an encrypted file");
    }
}
