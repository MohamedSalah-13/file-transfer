package com.hamza.filetransfer.client;

import javafx.beans.property.*;

public class UserItem {
    private final StringProperty username = new SimpleStringProperty();
    private final StringProperty lastPreview = new SimpleStringProperty("");
    private final StringProperty lastTime = new SimpleStringProperty("");   // NEW
    private final LongProperty lastActivityMillis = new SimpleLongProperty(0); // NEW
    private final IntegerProperty unreadCount = new SimpleIntegerProperty(0);

    public UserItem(String username) {
        this.username.set(username);
    }

    public StringProperty usernameProperty() { return username; }
    public StringProperty lastPreviewProperty() { return lastPreview; }
    public StringProperty lastTimeProperty() { return lastTime; } // NEW
    public LongProperty lastActivityMillisProperty() { return lastActivityMillis; } // NEW
    public IntegerProperty unreadCountProperty() { return unreadCount; }

    public String getUsername() { return username.get(); }

    public void bumpUnread() { unreadCount.set(unreadCount.get() + 1); }
    public void clearUnread() { unreadCount.set(0); }

    public void setLastPreview(String preview) {
        lastPreview.set(preview == null ? "" : preview);
    }

    public void updateLastActivity(long millis, String timeLabel) {
        lastActivityMillis.set(Math.max(lastActivityMillis.get(), millis));
        if (timeLabel != null) lastTime.set(timeLabel);
    }
}
