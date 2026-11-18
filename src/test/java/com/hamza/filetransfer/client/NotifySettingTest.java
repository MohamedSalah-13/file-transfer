package com.hamza.filetransfer.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class NotifySettingTest {

    @TempDir
    Path dir;

    @Test
    void onUntilSwitchedOff() throws Exception {
        Path file = dir.resolve("client.properties");
        assertTrue(NotifySetting.load(file).enabled(), "on by default");
        new LastLogin("10.0.0.5", 5000, "alice").save(file);
        new NotifySetting(false).save(file);
        assertFalse(NotifySetting.load(file).enabled());
        assertEquals(new LastLogin("10.0.0.5", 5000, "alice"), LastLogin.load(file), "other settings kept");
        new NotifySetting(true).save(file);
        assertTrue(NotifySetting.load(file).enabled());
    }

    @Test
    void anythingButFalseMeansOn() throws Exception {
        Path file = dir.resolve("client.properties");
        Files.writeString(file, "notifications=maybe\n");
        assertTrue(NotifySetting.load(file).enabled());
    }
}
