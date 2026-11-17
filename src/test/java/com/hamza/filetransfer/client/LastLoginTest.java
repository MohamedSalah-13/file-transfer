package com.hamza.filetransfer.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LastLoginTest {

    @TempDir
    Path dir;

    @Test
    void firstStartUsesTheDefaults() {
        assertEquals(LastLogin.DEFAULT, LastLogin.load(dir.resolve("client.properties")));
    }

    @Test
    void savedLoginComesBackAndHasNoPassword() throws Exception {
        Path file = dir.resolve("sub").resolve("client.properties"); // folder created on save
        new LastLogin("192.168.1.20", 5050, "سارة").save(file);
        assertEquals(new LastLogin("192.168.1.20", 5050, "سارة"), LastLogin.load(file));
        Properties saved = new Properties();
        try (var r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            saved.load(r);
        }
        assertEquals(Set.of("host", "port", "user"), saved.stringPropertyNames());
        try (var files = Files.list(file.getParent())) {
            assertEquals(1, files.count(), "no temp file left behind");
        }
    }

    @Test
    void badOrMissingValuesFallBackOneByOne() throws Exception {
        Path file = dir.resolve("client.properties");
        Files.writeString(file, "host=office-pc\nport=99999\n");
        assertEquals(new LastLogin("office-pc", 5000, "user1"), LastLogin.load(file));
        Files.writeString(file, "host=\nport=abc\nuser=bob\n");
        assertEquals(new LastLogin("127.0.0.1", 5000, "bob"), LastLogin.load(file));
    }

    @Test
    void aBrokenFileIsIgnored() throws Exception {
        Path file = dir.resolve("client.properties");
        Files.writeString(file, "user=\\u12"); // malformed escape makes Properties.load throw
        assertEquals(LastLogin.DEFAULT, LastLogin.load(file));
    }
}
