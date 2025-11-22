package ru.study.ui.fx.controller;

import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;

public class ProgressController {
    @FXML public ProgressBar progressBar;
    @FXML public Label titleLabel;
    @FXML public Label messageLabel;

    @FXML
    public void initialize() {
        progressBar.setProgress(0);
        titleLabel.setText("Working...");
        messageLabel.setText("");
    }
}
