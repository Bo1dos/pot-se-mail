package ru.study.ui.fx.controller;

import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TreeView;

public class MainWindowController {

    @FXML public TreeView<?> foldersTree;
    @FXML public TableView<?> inboxTable;
    @FXML public Label statusLabel;
    @FXML public Label bottomStatus;

    @FXML
    public void initialize() {
        statusLabel.setText("Status: ready (MVP)");
        bottomStatus.setText("No account configured");
    }
}
