package com.hamza.filetransfer.client;

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
}
