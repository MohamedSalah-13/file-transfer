package com.hamza.filetransfer.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AppearanceTest {

    @TempDir
    Path dir;

    @Test
    void firstStartIsEnglishAndTheFirstTheme() {
        assertEquals(Appearance.DEFAULT, Appearance.load(dir.resolve("client.properties")));
    }

    @Test
    void savedChoiceComesBack() throws Exception {
        Path file = dir.resolve("client.properties");
        new Appearance("ar", "Nord Dark").save(file);
        assertEquals(new Appearance("ar", "Nord Dark"), Appearance.load(file));
    }

    @Test
    void anUnknownLanguageGivesEnglish() throws Exception {
        Path file = dir.resolve("client.properties");
        Files.writeString(file, "language=fr\ntheme=Dracula\n");
        assertEquals(new Appearance("en", "Dracula"), Appearance.load(file));
    }

    @Test
    void loginAndAppearanceDontOverwriteEachOther() throws Exception {
        Path file = dir.resolve("client.properties");
        new LastLogin("192.168.1.20", 5050, "bob").save(file);
        new Appearance("ar", "Dracula").save(file);
        new LastLogin("10.0.0.5", 5000, "alice").save(file);
        assertEquals(new Appearance("ar", "Dracula"), Appearance.load(file));
        assertEquals(new LastLogin("10.0.0.5", 5000, "alice"), LastLogin.load(file));
    }

    @Test
    void savingOverABrokenFileReplacesIt() throws Exception {
        Path file = dir.resolve("client.properties");
        Files.writeString(file, "user=\\u12"); // malformed escape
        new Appearance("ar", "Dracula").save(file);
        assertEquals(new Appearance("ar", "Dracula"), Appearance.load(file));
        assertEquals(LastLogin.DEFAULT, LastLogin.load(file));
    }
}
