package com.hamza.filetransfer.server;

import com.hamza.filetransfer.server.AccountStore.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AccountStoreTest {

    @TempDir
    Path dir;

    private AccountStore newStore() throws Exception {
        return new AccountStore(dir.resolve("accounts.properties"));
    }

    @Test
    void firstLoginRegistersAndLaterLoginsNeedTheSamePassword() throws Exception {
        AccountStore store = newStore();

        AccountStore.Result first = store.authenticate("Ali", "secret1".toCharArray());
        assertEquals(Status.REGISTERED, first.status());
        assertEquals("Ali", first.username());

        assertEquals(Status.OK, store.authenticate("Ali", "secret1".toCharArray()).status());
        assertEquals(Status.WRONG_PASSWORD, store.authenticate("Ali", "wrong-pw".toCharArray()).status());
    }

    @Test
    void namesAreCaseInsensitiveAndReturnTheRegisteredSpelling() throws Exception {
        AccountStore store = newStore();
        store.authenticate("Ali", "secret1".toCharArray());

        AccountStore.Result again = store.authenticate("ALI", "secret1".toCharArray());
        assertEquals(Status.OK, again.status());
        assertEquals("Ali", again.username());

        assertEquals("Ali", store.canonicalName("aLi"));
        assertNull(store.canonicalName("nobody"));
        assertTrue(store.exists("ali"));
    }

    @Test
    void newAccountsNeedAStrongEnoughPassword() throws Exception {
        AccountStore store = newStore();

        assertEquals(Status.WEAK_PASSWORD, store.authenticate("bob", "12345".toCharArray()).status());
        assertFalse(store.exists("bob"));
    }

    @Test
    void repeatedFailuresLockTheAccountEvenForTheRightPassword() throws Exception {
        AccountStore store = newStore();
        store.authenticate("Ali", "secret1".toCharArray());

        for (int i = 0; i < 5; i++) store.authenticate("Ali", "wrong-pw".toCharArray());

        assertEquals(Status.LOCKED, store.authenticate("Ali", "secret1".toCharArray()).status());
    }

    @Test
    void accountsSurviveARestartAndAreNotStoredInClearText() throws Exception {
        newStore().authenticate("Ali", "secret1".toCharArray());

        assertEquals(Status.OK, newStore().authenticate("Ali", "secret1".toCharArray()).status());
        assertFalse(Files.readString(dir.resolve("accounts.properties")).contains("secret1"));
    }
}
