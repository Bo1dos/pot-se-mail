package ru.study.ui.fx.controller;

import javafx.fxml.FXML;
import javafx.scene.control.TextField;
import javafx.scene.web.HTMLEditor;

public class ComposerController {

    @FXML public TextField toField;
    @FXML public TextField subjectField;
    @FXML public HTMLEditor htmlEditor;

    @FXML
    public void initialize() {
        toField.setText("");
        subjectField.setText("");
        htmlEditor.setHtmlText("<p>Write message...</p>");
    }

    @FXML
    public void onSend() {
        // simple feedback; real send will be async
        subjectField.getScene().getWindow().hide(); // placeholder: close tab or show toast
    }
}
