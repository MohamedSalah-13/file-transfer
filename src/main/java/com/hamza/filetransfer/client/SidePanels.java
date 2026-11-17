package com.hamza.filetransfer.client;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.Region;

/**
 * The client's three columns: users, chat, transfers. The dividers can be dragged to resize the
 * side panels, and each side panel can be hidden and shown again at the width it had. Growing or
 * shrinking the window resizes the chat only. In Arabic the whole row is mirrored, so "first" is
 * the right-hand side.
 */
final class SidePanels {

    private final SplitPane split = new SplitPane();
    private final Region first;
    private final Region center;
    private final Region last;
    private final BooleanProperty firstShown = new SimpleBooleanProperty();
    private final BooleanProperty lastShown = new SimpleBooleanProperty();
    private double firstWidth;
    private double lastWidth;
    private boolean placed; // dividers are placed once the split pane has a real width

    SidePanels(Region first, Region center, Region last, PanelLayout layout) {
        this.first = first;
        this.center = center;
        this.last = last;
        firstWidth = layout.usersWidth();
        lastWidth = layout.transfersWidth();

        first.setMinWidth(200);
        last.setMinWidth(260);
        center.setMinWidth(360);
        SplitPane.setResizableWithParent(first, false);
        SplitPane.setResizableWithParent(last, false);
        split.getStyleClass().add("panels");

        split.getItems().add(center);
        firstShown.set(layout.usersShown());
        lastShown.set(layout.transfersShown());
        if (layout.usersShown()) split.getItems().addFirst(first);
        if (layout.transfersShown()) split.getItems().addLast(last);

        firstShown.addListener((obs, was, show) -> {
            rememberWidths(); // the user may have dragged a divider since the last change
            if (show) split.getItems().addFirst(first);
            else split.getItems().remove(first);
            placeDividers();
        });
        lastShown.addListener((obs, was, show) -> {
            rememberWidths();
            if (show) split.getItems().addLast(last);
            else split.getItems().remove(last);
            placeDividers();
        });
        split.widthProperty().addListener((obs, old, w) -> {
            if (!placed && w.doubleValue() > 0) {
                placed = true;
                placeDividers();
            }
        });
    }

    SplitPane node() {
        return split;
    }

    /** Whether the users panel is shown; bind a toggle button to it. */
    BooleanProperty usersShownProperty() {
        return firstShown;
    }

    /** Whether the transfers panel is shown; bind a toggle button to it. */
    BooleanProperty transfersShownProperty() {
        return lastShown;
    }

    /** The layout as it is now, for saving. A hidden panel keeps the width it had. */
    PanelLayout current() {
        return new PanelLayout(firstShown.get(), widthOf(first, firstWidth), lastShown.get(), widthOf(last, lastWidth));
    }

    /** Takes the current widths of the shown side panels as the ones to keep. */
    private void rememberWidths() {
        firstWidth = widthOf(first, firstWidth);
        lastWidth = widthOf(last, lastWidth);
    }

    private double widthOf(Region panel, double remembered) {
        double w = split.getItems().contains(panel) ? panel.getWidth() : 0;
        return w > 0 ? Math.clamp(w, PanelLayout.MIN_WIDTH, PanelLayout.MAX_WIDTH) : remembered;
    }

    /**
     * Puts the dividers where the side panels get their remembered widths. A divider position is
     * the fraction of the whole width at the divider's middle, so each side panel also gives up
     * half a divider.
     */
    private void placeDividers() {
        double total = split.getWidth();
        if (total <= 0) return;
        double half = dividerWidth() / 2;
        boolean hasFirst = split.getItems().contains(first);
        boolean hasLast = split.getItems().contains(last);
        double p0 = (firstWidth + half) / total;
        double p1 = 1 - (lastWidth + half) / total;
        if (hasFirst && hasLast) split.setDividerPositions(p0, p1);
        else if (hasFirst) split.setDividerPositions(p0);
        else if (hasLast) split.setDividerPositions(p1);
    }

    /** The divider's width from the stylesheet (its padding), 0 before the skin exists. */
    private double dividerWidth() {
        split.applyCss();
        return split.lookup(".split-pane-divider") instanceof Region d ? Math.max(d.getWidth(), d.prefWidth(-1)) : 0;
    }
}
