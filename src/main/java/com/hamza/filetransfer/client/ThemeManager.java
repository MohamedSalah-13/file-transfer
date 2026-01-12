package com.hamza.filetransfer.client;

import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.PrimerLight;
import javafx.application.Application;

public final class ThemeManager {
    private static boolean dark = false;

    private ThemeManager() {}

    public static void applyLight() {
        dark = false;
        Application.setUserAgentStylesheet(new PrimerLight().getUserAgentStylesheet());
    }

    public static void applyDark() {
        dark = true;
        Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());
    }

    public static boolean isDark() {
        return dark;
    }

    public static void toggle() {
        if (dark) applyLight();
        else applyDark();
    }
}
