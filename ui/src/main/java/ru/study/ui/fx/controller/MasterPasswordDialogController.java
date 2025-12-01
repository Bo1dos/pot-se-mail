package ru.study.ui.fx.controller;

import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.stage.Stage;
import ru.study.core.event.NotificationEvent;
import ru.study.core.event.NotificationLevel;
import ru.study.core.event.bus.EventBus;
import ru.study.core.exception.CoreException;
import ru.study.service.api.MasterPasswordService;

import java.util.Optional;

public class MasterPasswordDialogController {

    @FXML public PasswordField passwordField;
    @FXML public PasswordField confirmField;
    @FXML public Label infoLabel;

    private final MasterPasswordService masterPasswordService;
    private final EventBus eventBus;

    // ServiceLocator should construct controller with dependencies
    public MasterPasswordDialogController(MasterPasswordService masterPasswordService, EventBus eventBus) {
        this.masterPasswordService = masterPasswordService;
        this.eventBus = eventBus;
    }

    @FXML
    public void initialize() {
        infoLabel.setText("");
        // hide confirm if master is already in-memory? we keep it visible — user may initialize
    }

    @FXML
    public void onUnlock() {
        char[] pwd = passwordField.getText() == null ? null : passwordField.getText().toCharArray();
        if (pwd == null || pwd.length == 0) {
            infoLabel.setText("Password required");
            return;
        }
        try {
            boolean ok = masterPasswordService.verifyMasterPassword(pwd);
            if (ok) {
                eventBus.publish(new NotificationEvent(NotificationLevel.SUCCESS, "Unlocked", null));
                close();
            } else {
                infoLabel.setText("Invalid master password");
                eventBus.publish(new NotificationEvent(NotificationLevel.ERROR, "Master password invalid", null));
            }
        } catch (CoreException e) {
            infoLabel.setText("Error: " + e.getMessage());
            eventBus.publish(new NotificationEvent(NotificationLevel.ERROR, "Error verifying master password: " + e.getMessage(), e));
        }
    }

    @FXML
    public void onInitialize() {
        String p = passwordField.getText();
        String c = confirmField.getText();
        if (p == null || p.isBlank()) {
            infoLabel.setText("Enter new password");
            return;
        }
        if (!p.equals(c)) {
            infoLabel.setText("Passwords do not match");
            return;
        }
        try {
            masterPasswordService.initializeMasterPassword(p.toCharArray());
            eventBus.publish(new NotificationEvent(NotificationLevel.SUCCESS, "Master password initialized", null));
            close();
        } catch (Exception e) {
            infoLabel.setText("Failed to initialize: " + e.getMessage());
            eventBus.publish(new NotificationEvent(NotificationLevel.ERROR, "Failed to init master password: " + e.getMessage(), e));
        }
    }

    @FXML
    public void onCancel() {
        close();
    }

    private void close() {
        Stage s = (Stage) passwordField.getScene().getWindow();
        s.close();
    }
}
