package com.hamza.filetransfer.client;

import javafx.geometry.Insets;
import javafx.geometry.NodeOrientation;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;

/**
 * The ⚙ Settings window: a "Connection" tab (server, user, password, connect) and an
 * "Appearance" tab (language, theme). Built once and shown again as needed; it belongs to the
 * main window, so it stays in front of it and closes with it.
 */
final class SettingsWindow {

    private final Stage stage = new Stage();
    private final TabPane tabs = new TabPane();
    private final Tab connection = new Tab();
    private final Tab appearance = new Tab();

    SettingsWindow(Stage owner, Node connectionContent, Node appearanceContent, String css) {
        connection.textProperty().bind(I18n.bind("settings_connection"));
        appearance.textProperty().bind(I18n.bind("settings_appearance"));
        connection.setContent(connectionContent);
        appearance.setContent(appearanceContent);
        for (Tab t : new Tab[]{connection, appearance}) {
            t.setClosable(false);
            if (t.getContent() instanceof javafx.scene.layout.Region r) r.setPadding(new Insets(16));
        }
        tabs.getTabs().addAll(connection, appearance);
        tabs.getStyleClass().add("settings");

        Scene scene = new Scene(tabs);
        scene.getStylesheets().add(css);
        stage.setScene(scene);
        stage.initOwner(owner);
        stage.titleProperty().bind(I18n.bind("settings"));
        stage.getIcons().setAll(owner.getIcons());
        stage.setMinWidth(480);
        applyOrientation();
    }

    Stage stage() {
        return stage;
    }

    void showConnection() {
        show(connection);
    }

    void showAppearance() {
        show(appearance);
    }

    void hide() {
        stage.hide();
    }

    /** Follows the language: Arabic lays the window out right to left. */
    void applyOrientation() {
        tabs.setNodeOrientation(I18n.isRightToLeft() ? NodeOrientation.RIGHT_TO_LEFT : NodeOrientation.LEFT_TO_RIGHT);
        stage.sizeToScene();
    }

    private void show(Tab tab) {
        tabs.getSelectionModel().select(tab);
        if (!stage.isShowing()) {
            stage.sizeToScene();
            stage.show();
        }
        stage.toFront();
    }
}
