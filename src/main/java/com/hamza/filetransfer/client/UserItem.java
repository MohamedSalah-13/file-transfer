package com.hamza.filetransfer.client;

import javafx.beans.binding.Bindings;
import javafx.beans.binding.StringBinding;
import javafx.beans.property.*;

public class UserItem {
    private final StringProperty username = new SimpleStringProperty();
    private final StringProperty lastPreview = new SimpleStringProperty("");
    private final LongProperty lastActivityMillis = new SimpleLongProperty(0);
    private final IntegerProperty unreadCount = new SimpleIntegerProperty(0);

    // Derived from the activity time, so it uses the same format as chat bubbles
    // and re-translates when the language changes ("Yesterday" / "أمس").
    private final StringBinding lastTime = Bindings.createStringBinding(
            () -> I18n.messageTime(lastActivityMillis.get()),
            lastActivityMillis, I18n.bundleProperty());

    public UserItem(String username) {
        this.username.set(username);
    }

    public StringProperty usernameProperty() { return username; }
    public StringProperty lastPreviewProperty() { return lastPreview; }
    public StringBinding lastTimeProperty() { return lastTime; }
    public LongProperty lastActivityMillisProperty() { return lastActivityMillis; }
    public IntegerProperty unreadCountProperty() { return unreadCount; }

    public String getUsername() { return username.get(); }

    public void bumpUnread() { unreadCount.set(unreadCount.get() + 1); }
    public void clearUnread() { unreadCount.set(0); }

    public void setLastPreview(String preview) {
        lastPreview.set(preview == null ? "" : preview);
    }

    /** Keeps the most recent activity; older timestamps are ignored. */
    public void updateLastActivity(long millis) {
        lastActivityMillis.set(Math.max(lastActivityMillis.get(), millis));
    }
}
