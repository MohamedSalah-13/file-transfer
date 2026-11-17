package com.hamza.filetransfer.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class KnownServersTest {

    @TempDir
    Path dir;

    @Test
    void trustedFingerprintsArePerHostAndPortAndSurviveARestart() throws Exception {
        Path file = dir.resolve("sub/known_servers.properties");
        KnownServers known = new KnownServers(file);
        assertNull(known.fingerprint("192.168.1.10", 5000));

        known.trust("192.168.1.10", 5000, "AA:BB");

        KnownServers reloaded = new KnownServers(file);
        assertEquals("AA:BB", reloaded.fingerprint("192.168.1.10", 5000));
        assertNull(reloaded.fingerprint("192.168.1.10", 5001), "another port is another server");
        assertNull(reloaded.fingerprint("192.168.1.11", 5000));
    }

    @Test
    void hostNamesAreCaseInsensitive() throws Exception {
        KnownServers known = new KnownServers(dir.resolve("k.properties"));
        known.trust("Office-PC", 5000, "AA:BB");
        assertEquals("AA:BB", known.fingerprint("office-pc ", 5000));
    }

    @Test
    void twoClientWindowsKeepEachOthersTrust() throws Exception {
        Path file = dir.resolve("k.properties");
        KnownServers first = new KnownServers(file);
        KnownServers second = new KnownServers(file); // opened before the first one trusted anything

        first.trust("server-a", 5000, "AA:AA");
        assertEquals("AA:AA", second.fingerprint("server-a", 5000), "sees the other window's decision");

        second.trust("server-b", 5000, "BB:BB");
        KnownServers reloaded = new KnownServers(file);
        assertEquals("AA:AA", reloaded.fingerprint("server-a", 5000), "not overwritten by the other window");
        assertEquals("BB:BB", reloaded.fingerprint("server-b", 5000));
    }

    @Test
    void trustingAgainReplacesTheFingerprint() throws Exception {
        KnownServers known = new KnownServers(dir.resolve("k.properties"));
        known.trust("host", 5000, "AA:BB");
        known.trust("host", 5000, "CC:DD");
        assertEquals("CC:DD", known.fingerprint("host", 5000));
    }
}
