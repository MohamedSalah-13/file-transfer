package com.hamza.filetransfer.client;

import atlantafx.base.theme.*;
import javafx.application.Application;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

public class ThemeManager {

    public record ThemeOption(String name, Theme theme) {
        @Override public String toString() { return name; }
    }

    private static final ObservableList<ThemeOption> THEMES = FXCollections.observableArrayList(
            new ThemeOption("Primer Light", new PrimerLight()),
            new ThemeOption("Primer Dark", new PrimerDark()),
            new ThemeOption("Nord Light", new NordLight()),
            new ThemeOption("Nord Dark", new NordDark()),
            new ThemeOption("Cupertino Light", new CupertinoLight()),
            new ThemeOption("Cupertino Dark", new CupertinoDark()),
            new ThemeOption("Dracula", new Dracula())
    );

    public static ObservableList<ThemeOption> getThemes() {
        return THEMES;
    }

    public static void applyTheme(ThemeOption option) {
        if (option != null && option.theme() != null) {
            Application.setUserAgentStylesheet(option.theme().getUserAgentStylesheet());
        }
    }
}
