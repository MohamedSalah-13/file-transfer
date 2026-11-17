package com.hamza.filetransfer.shared;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SafeNamesTest {

    @TempDir
    Path base;

    @Test
    void sanitizeFileNameKeepsOnlyTheLastPathSegment() {
        assertEquals("passwd", SafeNames.sanitizeFileName("../../etc/passwd"));
        assertEquals("win.ini", SafeNames.sanitizeFileName("..\\..\\Windows\\win.ini"));
        assertEquals("a.txt", SafeNames.sanitizeFileName("C:\\Users\\x\\a.txt"));
    }

    @Test
    void sanitizeFileNameRemovesDangerousCharactersAndDots() {
        assertEquals("a_b__.txt", SafeNames.sanitizeFileName("a:b*?.txt"));
        assertEquals("hidden", SafeNames.sanitizeFileName("...hidden"));
        assertEquals("name", SafeNames.sanitizeFileName("name. . "));
        assertEquals("file", SafeNames.sanitizeFileName(".."));
        assertEquals("file", SafeNames.sanitizeFileName(""));
        assertEquals("file", SafeNames.sanitizeFileName(null));
    }

    @Test
    void sanitizeFileNamePrefixesWindowsDeviceNames() {
        assertEquals("_CON.txt", SafeNames.sanitizeFileName("CON.txt"));
        assertEquals("_nul", SafeNames.sanitizeFileName("nul"));
    }

    @Test
    void sanitizeFileNameShortensLongNamesButKeepsTheExtension() {
        String name = SafeNames.sanitizeFileName("x".repeat(500) + ".pdf");
        assertEquals(SafeNames.MAX_FILE_NAME_LENGTH, name.length());
        assertTrue(name.endsWith(".pdf"));
    }

    @Test
    void usernamesAllowLettersInAnyLanguageDigitsUnderscoreAndDash() {
        assertTrue(SafeNames.isValidUsername("Ali"));
        assertTrue(SafeNames.isValidUsername("علي_1-x"));
        assertTrue(SafeNames.isValidUsername("a".repeat(SafeNames.MAX_USERNAME_LENGTH)));
    }

    @Test
    void usernamesRejectPathsSpacesDeviceNamesAndBadLengths() {
        assertFalse(SafeNames.isValidUsername(".."));
        assertFalse(SafeNames.isValidUsername("a/b"));
        assertFalse(SafeNames.isValidUsername("a b"));
        assertFalse(SafeNames.isValidUsername("CON"));
        assertFalse(SafeNames.isValidUsername(""));
        assertFalse(SafeNames.isValidUsername(null));
        assertFalse(SafeNames.isValidUsername("a".repeat(SafeNames.MAX_USERNAME_LENGTH + 1)));
    }

    @Test
    void usernamesAreNormalizedAndCompareCaseInsensitively() {
        assertEquals("Ali", SafeNames.normalizeUsername("  Ali "));
        assertEquals(SafeNames.accountKey("Ali"), SafeNames.accountKey("ALI"));
    }

    @Test
    void messageIdsMustBeUuids() {
        assertTrue(SafeNames.isValidMessageId(UUID.randomUUID().toString()));
        assertFalse(SafeNames.isValidMessageId("../../accounts"));
        assertFalse(SafeNames.isValidMessageId("not-a-uuid-but-exactly-36-chars-xxxx"));
        assertFalse(SafeNames.isValidMessageId(null));
    }

    @Test
    void resolveInsideAllowsChildren() {
        assertEquals(base.toAbsolutePath().normalize().resolve("a.meta"), SafeNames.resolveInside(base, "a.meta"));
    }

    @Test
    void resolveInsideRejectsEscapes() {
        assertThrows(SecurityException.class, () -> SafeNames.resolveInside(base, "../x"));
        assertThrows(SecurityException.class, () -> SafeNames.resolveInside(base, "a/../../x"));
        assertThrows(SecurityException.class, () -> SafeNames.resolveInside(base, "."));
        assertThrows(SecurityException.class,
                () -> SafeNames.resolveInside(base, base.getRoot().resolve("elsewhere").toString()));
    }
}
