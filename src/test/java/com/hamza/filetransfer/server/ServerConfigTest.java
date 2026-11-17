package com.hamza.filetransfer.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ServerConfigTest {

    @TempDir
    Path work;

    @Test
    void defaultsWithoutArgsOrFile() throws Exception {
        ServerConfig c = ServerConfig.load(List.of(), work);
        assertEquals(5000, c.port());
        assertEquals(work.resolve("server-storage").toAbsolutePath().normalize(), c.storageDir());
        assertEquals(c.storageDir().resolve("users"), c.usersDir());
        assertEquals(c.storageDir().resolve("tls"), c.tlsDir());
        assertEquals(c.storageDir().resolve("accounts.properties"), c.accountsFile());
    }

    @Test
    void optionsWithSpaceOrEquals() throws Exception {
        Path data = work.resolve("data dir");
        assertEquals(new ServerConfig(5050, data), ServerConfig.load(List.of("--port", "5050", "--storage", data.toString()), work));
        assertEquals(new ServerConfig(6000, data), ServerConfig.load(List.of("--port=6000", "--storage=" + data), work));
    }

    @Test
    void configFileIsUsedAndArgsOverrideIt() throws Exception {
        Files.writeString(work.resolve("server.properties"), "port=7000\nstorage=ft-data\n");
        assertEquals(new ServerConfig(7000, work.resolve("ft-data")), ServerConfig.load(List.of(), work),
                "a relative storage path is inside the working directory");
        assertEquals(new ServerConfig(7001, work.resolve("ft-data")), ServerConfig.load(List.of("--port", "7001"), work));
    }

    @Test
    void mistakesGiveAReadableMessage() {
        assertMessage(List.of("--port", "abc"), "must be a number");
        assertMessage(List.of("--port", "70000"), "between 1 and 65535");
        assertMessage(List.of("--port", "0"), "between 1 and 65535");
        assertMessage(List.of("--port"), "Missing value after --port");
        assertMessage(List.of("--prot=5000"), "Unknown option \"--prot=5000\"");
        assertMessage(List.of("5000"), "Unknown option \"5000\"");
    }

    @Test
    void aBadPortInTheFileNamesTheFile() throws Exception {
        Files.writeString(work.resolve("server.properties"), "port=five\n");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ServerConfig.load(List.of(), work));
        assertTrue(e.getMessage().contains("server.properties"), e.getMessage());
    }

    @Test
    void theInstalledServerKeepsItsDataInTheUserHome() throws Exception {
        Path cwd = work.resolve("Program Files").resolve("File Transfer");
        Path home = work.resolve("home");
        assertEquals(cwd, ServerConfig.workDir(false, cwd, home), "run from source: the working directory");
        Path installed = ServerConfig.workDir(true, cwd, home);
        assertEquals(home.resolve(".file-transfer").resolve("server"), installed);
        assertEquals(installed.resolve("server-storage").toAbsolutePath().normalize(),
                ServerConfig.load(List.of(), installed).storageDir());
    }

    private void assertMessage(List<String> args, String expected) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ServerConfig.load(args, work));
        assertTrue(e.getMessage().contains(expected), e.getMessage());
    }
}
