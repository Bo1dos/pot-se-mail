package ru.study.ui.fx.controller;

import javafx.fxml.FXML;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

public class LoginDialogController {

    @FXML public TextField emailField;
    @FXML public TextField imapField;

    @FXML
    public void initialize() {
        emailField.setText("");
        imapField.setText("imap.example.com");
    }

    @FXML
    public void onTest() {
        // show quick dialog
        System.out.println("Test connection for " + emailField.getText());
    }

    @FXML
    public void onSave() {
        // close window for now
        Stage s = (Stage) emailField.getScene().getWindow();
        s.close();
    }

    @FXML
    public void onCancel() {
        Stage s = (Stage) emailField.getScene().getWindow();
        s.close();
    }
}
