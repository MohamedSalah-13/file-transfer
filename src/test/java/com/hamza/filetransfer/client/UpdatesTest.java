package com.hamza.filetransfer.client;

import com.hamza.filetransfer.shared.ErrorCode;
import org.junit.jupiter.api.Test;

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
}
