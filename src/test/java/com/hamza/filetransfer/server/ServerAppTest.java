package com.hamza.filetransfer.server;

import com.hamza.filetransfer.client.I18n;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.BindException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** The server window's start-up messages (no window needed: they are string bindings). */
class ServerAppTest {

    @AfterEach
    void english() {
        I18n.setLanguage("en");
    }

    @Test
    void aBusyPortSaysWhatToChangeAndFollowsTheLanguage() {
        ServerConfig config = new ServerConfig(5050, Path.of("data"));
        var message = ServerApp.startFailure(new BindException("Address already in use"), config);
        I18n.setLanguage("en");
        assertTrue(message.get().startsWith("port 5050 is already in use"), message.get());
        assertTrue(message.get().contains("--port <number>") && message.get().contains("server.properties"), message.get());
        I18n.setLanguage("ar");
        assertTrue(message.get().startsWith("المنفذ 5050 مستخدم بالفعل"), message.get());
        assertTrue(message.get().contains("‪--port <رقم>‬"), "the option stays left to right in Arabic");
    }

    @Test
    void otherErrorsShowTheirOwnMessageLeftToRight() {
        var message = ServerApp.startFailure(new IllegalArgumentException("Port must be between 1 and 65535, got 0"), null);
        assertEquals("‪Port must be between 1 and 65535, got 0‬", message.get());
    }

    @Test
    void theWindowKeepsItsLanguageAndThemeApartFromTheClient() {
        Path file = ServerApp.appearanceFile();
        assertEquals(Path.of(System.getProperty("user.home"), ".file-transfer", "server-ui.properties"), file);
    }
}
