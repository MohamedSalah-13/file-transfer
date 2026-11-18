package com.hamza.filetransfer.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LocalFilesTest {

    @TempDir
    Path dir;

    @Test
    void rememberedFilesAreFoundAgainAfterARestart() throws Exception {
        Path index = dir.resolve("local-files.properties");
        Path report = Files.writeString(dir.resolve("my report.pdf"), "x");
        new LocalFiles(index).remember("11111111-1111-1111-1111-111111111111", report);

        LocalFiles reopened = new LocalFiles(index);
        assertEquals(report.toAbsolutePath(), reopened.find("11111111-1111-1111-1111-111111111111"));
        assertNull(reopened.find("22222222-2222-2222-2222-222222222222"), "never downloaded");
        assertNull(reopened.find(null));
    }

    @Test
    void aMovedOrDeletedFileIsNotFound() throws Exception {
        Path index = dir.resolve("local-files.properties");
        Path report = Files.writeString(dir.resolve("report.pdf"), "x");
        LocalFiles files = new LocalFiles(index);
        files.remember("id-1", report);
        Files.delete(report);
        assertNull(files.find("id-1"));
    }
}
