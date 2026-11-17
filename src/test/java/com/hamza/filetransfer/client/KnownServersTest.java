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
    void trustingAgainReplacesTheFingerprint() throws Exception {
        KnownServers known = new KnownServers(dir.resolve("k.properties"));
        known.trust("host", 5000, "AA:BB");
        known.trust("host", 5000, "CC:DD");
        assertEquals("CC:DD", known.fingerprint("host", 5000));
    }
}
