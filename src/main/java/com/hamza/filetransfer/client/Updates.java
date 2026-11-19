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
     * The program's real version, set by the installer's launchers ({@code packaging/package.ps1}). It wins
     * over the launcher's own {@code jpackage.app-version}, which on macOS reads 1.x.y for a 0.x.y release:
     * macOS refuses a package version that starts with 0.
     */
    public static final String VERSION_PROPERTY = "filetransfer.version";

    /**
     * The installed copy's version comes from the launcher, so a copy run from the IDE has none and is
     * never offered a release.
     * <p>
     * Releases are found by their Windows installer on every system. Where the library cannot run an
     * installer (Linux, macOS) it offers the release's page instead, which also holds the .deb and .dmg
     * files ({@code .github/workflows/packages.yml}).
     */
    public static AppUpdater updater() {
        return updater(System.getProperty(VERSION_PROPERTY));
    }

    static AppUpdater updater(String packagedVersion) {
        AppUpdater.Builder builder = AppUpdater.builder("File Transfer")
                .source(ReleaseSource.github("MohamedSalah-13/file-transfer").asset("*-setup.exe"));
        if (packagedVersion != null && !packagedVersion.isBlank()) {
            builder.currentVersion(packagedVersion.strip());
        }
        return builder.build();
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
