package com.hamza.filetransfer.client;

import com.hamza.filetransfer.shared.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class UpdatesTest {

    @Test
    void aNewerServerMeansThisCopyIsBehind() {
        assertTrue(Updates.serverIsNewer(new AppError(ErrorCode.VERSION_MISMATCH, "3", "2")));
    }

    @Test
    void anOlderOrEqualServerLeavesNothingToInstallHere() {
        assertFalse(Updates.serverIsNewer(new AppError(ErrorCode.VERSION_MISMATCH, "1", "2")));
        assertFalse(Updates.serverIsNewer(new AppError(ErrorCode.VERSION_MISMATCH, "2", "2")));
        // A server from before versioning is reported as protocol 0.
        assertFalse(Updates.serverIsNewer(new AppError(ErrorCode.VERSION_MISMATCH, "0", "2")));
    }

    @Test
    void anyOtherFailureIsNotAboutVersions() {
        assertFalse(Updates.serverIsNewer(new AppError(AppError.CANNOT_CONNECT)));
        assertFalse(Updates.serverIsNewer(new AppError(ErrorCode.VERSION_MISMATCH)));
        assertFalse(Updates.serverIsNewer(new AppError(ErrorCode.VERSION_MISMATCH, "new", "old")));
    }

    @Test
    void theLaunchersRealVersionIsWhatReleasesAreComparedWith() {
        // macOS packages 0.4.2 as 1.4.2; the launcher passes the real number with -Dfiletransfer.version.
        assertEquals("0.4.2", Updates.updater("0.4.2").currentVersion().orElseThrow().toString());
    }

    @Test
    void withoutItTheLaunchersOwnVersionCounts() {
        assertEquals(Optional.ofNullable(System.getProperty("jpackage.app-version")).isPresent(),
                Updates.updater(null).currentVersion().isPresent());
        assertEquals(Updates.updater(null).currentVersion(), Updates.updater(" ").currentVersion());
    }
}
