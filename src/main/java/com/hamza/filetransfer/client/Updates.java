package com.hamza.filetransfer.client;

import com.hamza.filetransfer.shared.ErrorCode;
import com.hamza.updater.AppUpdater;
import com.hamza.updater.ReleaseSource;

/**
 * Where the program's new releases are. One installer carries the client and the server, so both look in
 * the same place and share what was downloaded; the shared desktop-updater library does the looking,
 * the download and the installation.
 */
public final class Updates {

    private Updates() {
    }

    /**
     * The installed copy's version comes from the launcher, so a copy run from the IDE has none and is
     * never offered a release.
     */
    public static AppUpdater updater() {
        return AppUpdater.builder("File Transfer")
                .source(ReleaseSource.github("MohamedSalah-13/file-transfer").asset("*-setup.exe"))
                .build();
    }

    /**
     * Whether a failed login says the server speaks a newer protocol than this copy
     * ({@code version_mismatch <server> <client>}): then it is this copy that is behind, and its update is
     * worth looking for at once. A server from before versioning reports as 0, which is older, not newer.
     */
    static boolean serverIsNewer(AppError error) {
        if (!ErrorCode.VERSION_MISMATCH.equals(error.code()) || error.args().size() < 2) {
            return false;
        }
        try {
            return Integer.parseInt(error.args().get(0)) > Integer.parseInt(error.args().get(1));
        } catch (NumberFormatException notVersions) {
            return false;
        }
    }
}
