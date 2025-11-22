package ru.study.ui.fx.controller;

import javafx.fxml.FXML;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;

public class FoldersController {

    @FXML public TreeView<String> foldersTree;

    @FXML
    public void initialize() {
        TreeItem<String> root = new TreeItem<>("Accounts");
        TreeItem<String> acc = new TreeItem<>("me@example.com");
        acc.getChildren().addAll(new TreeItem<>("INBOX"), new TreeItem<>("SENT"), new TreeItem<>("DRAFTS"));
        root.getChildren().add(acc);
        root.setExpanded(true);
        foldersTree.setRoot(root);
    }
}
