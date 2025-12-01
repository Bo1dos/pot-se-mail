package ru.study.ui.fx.controller;

import javafx.fxml.FXML;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;

/**
 * Small helper to display HTML message content.
 */
public class MessageViewController {

    @FXML public WebView webView;

    @FXML
    public void initialize() {
        WebEngine engine = webView.getEngine();
        engine.loadContent("<html><body><h2>Message Preview</h2><p>Select a message to view content.</p></body></html>");
    }

    public void showHtml(String html) {
        if (html == null) html = "<p>(no content)</p>";
        webView.getEngine().loadContent(html);
    }
}
