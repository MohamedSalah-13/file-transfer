package com.hamza.filetransfer.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PanelLayoutTest {

    @TempDir
    Path dir;

    @Test
    void firstStartShowsBothPanelsAtTheDefaultWidths() {
        assertEquals(PanelLayout.DEFAULT, PanelLayout.load(dir.resolve("client.properties")));
    }

    @Test
    void savedLayoutComesBackAndKeepsTheOtherSettings() throws Exception {
        Path file = dir.resolve("client.properties");
        new LastLogin("192.168.1.20", 5050, "alice").save(file);
        new PanelLayout(true, 380.4, false, 500).save(file);
        assertEquals(new PanelLayout(true, 380, false, 500), PanelLayout.load(file));
        assertEquals(new LastLogin("192.168.1.20", 5050, "alice"), LastLogin.load(file));
    }

    @Test
    void badValuesFallBackOneByOne() throws Exception {
        Path file = dir.resolve("client.properties");
        Files.writeString(file, """
                panel.users.shown=maybe
                panel.users.width=20
                panel.transfers.shown=false
                panel.transfers.width=wide
                """);
        assertEquals(new PanelLayout(true, 300, false, 420), PanelLayout.load(file));
    }
}
