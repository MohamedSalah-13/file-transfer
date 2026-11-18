package com.hamza.filetransfer.client;

import javafx.scene.control.ButtonBase;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.javafx.FontIcon;

/**
 * Icons for buttons and labels, from the Feather pack of Ikonli
 * ({@code org.kordamp.ikonli.feather.Feather}). They are font glyphs, so they stay sharp at any
 * size and AtlantaFX colors them like the text next to them in every theme.
 */
final class Icons {

    private Icons() {}

    static FontIcon of(Ikon icon) {
        return new FontIcon(icon);
    }

    /** Puts {@code icon} before the button's text (after it in Arabic, which mirrors the layout). */
    static <B extends ButtonBase> B on(B button, Ikon icon) {
        button.setGraphic(of(icon));
        return button;
    }
}
