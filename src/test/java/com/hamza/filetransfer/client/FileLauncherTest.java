package com.hamza.filetransfer.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FileLauncherTest {

    @Test
    void programsAndScriptsAreRecognized() {
        for (String name : new String[]{"setup.exe", "INSTALL.MSI", "run.bat", "x.cmd", "tool.ps1", "a.vbs", "app.jar", "link.lnk", "page.hta", "disk.iso"}) {
            assertTrue(FileLauncher.canRunPrograms(name), name);
        }
    }

    @Test
    void macAndLinuxProgramsAreRecognizedToo() {
        for (String name : new String[]{"start.command", "Setup.pkg", "App.dmg", "install.sh", "tool.AppImage", "app.desktop", "x.deb"}) {
            assertTrue(FileLauncher.canRunPrograms(name), name);
        }
    }

    @Test
    void windowsTricksWithTrailingDotsAndSpacesAreCaught() {
        assertTrue(FileLauncher.canRunPrograms("setup.exe."));
        assertTrue(FileLauncher.canRunPrograms("setup.exe  "));
        assertTrue(FileLauncher.canRunPrograms("invoice.pdf.exe"), "double extension: the last one counts");
    }

    @Test
    void documentsAndMediaAreNot() {
        for (String name : new String[]{"report.pdf", "photo.JPG", "notes.txt", "song.mp3", "data.xlsx", "archive.zip", "exe", "README"}) {
            assertFalse(FileLauncher.canRunPrograms(name), name);
        }
    }
}
