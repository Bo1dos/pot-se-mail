package ru.study.ui.fx.controller;

import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.Stage;
import ru.study.core.dto.AccountDTO;
import ru.study.core.event.NotificationEvent;
import ru.study.core.event.NotificationLevel;
import ru.study.core.event.bus.EventBus;
import ru.study.core.exception.CoreException;
import ru.study.mailadapter.model.AccountConfig;
import ru.study.service.api.AccountService;
import ru.study.service.dto.CreateAccountRequest;
import ru.study.service.dto.ConnectionTestResult;

public class AccountDialogController {

    @FXML public TextField emailField;
    @FXML public TextField imapField;
    @FXML public TextField imapPortField;
    @FXML public TextField smtpField;
    @FXML public TextField smtpPortField;
    @FXML public PasswordField passwordField;
    @FXML public CheckBox useTls;
    @FXML public Label infoLabel;

    private final AccountService accountService;
    private final EventBus eventBus;

    public AccountDialogController(AccountService accountService, EventBus eventBus) {
        this.accountService = accountService;
        this.eventBus = eventBus;
    }

    @FXML
    public void initialize() {
        imapField.setText("imap.yandex.ru");
        imapPortField.setText("993");
        smtpField.setText("smtp.yandex.ru");
        smtpPortField.setText("465");
        useTls.setSelected(true);
    }

    @FXML
    public void onTest() {
        try {
            CreateAccountRequest req = buildRequest();
            // Create account temporarily (persists) and test connection
            AccountDTO dto = accountService.createAccount(req);
            ConnectionTestResult res = accountService.testConnection(dto.id());
            String msg = (res.isImapOk() || res.isSmtpOk()) ? "Connection OK" : "Connection failed: " + res.getMessage();
            eventBus.publish(new NotificationEvent(NotificationLevel.INFO, "Test result: " + msg, null));
            infoLabel.setText("Test result: " + msg);
        } catch (CoreException e) {
            infoLabel.setText("Test failed: " + e.getMessage());
            eventBus.publish(new NotificationEvent(NotificationLevel.ERROR, "Test failed: " + e.getMessage(), e));
        } catch (Exception e) {
            infoLabel.setText("Error: " + e.getMessage());
            eventBus.publish(new NotificationEvent(NotificationLevel.ERROR, "Error testing account: " + e.getMessage(), e));
        }
    }

    @FXML
    public void onSave() {
        try {
            CreateAccountRequest req = buildRequest();
            AccountDTO dto = accountService.createAccount(req);
            eventBus.publish(new NotificationEvent(NotificationLevel.SUCCESS, "Account saved: " + dto.email(), null));
            close();
        } catch (CoreException e) {
            infoLabel.setText("Save failed: " + e.getMessage());
            eventBus.publish(new NotificationEvent(NotificationLevel.ERROR, "Failed to create account: " + e.getMessage(), e));
        }
    }

    @FXML
    public void onCancel() {
        close();
    }

    private CreateAccountRequest buildRequest() {
        CreateAccountRequest req = CreateAccountRequest.builder()
                .email(emailField.getText())
                .displayName("") // optional
                .imapHost(imapField.getText())
                .imapPort(parseIntSafe(imapPortField.getText()))
                .smtpHost(smtpField.getText())
                .smtpPort(parseIntSafe(smtpPortField.getText()))
                .password(passwordField.getText())
                .useTls(useTls.isSelected())
                .build();
        return req;
    }

    private Integer parseIntSafe(String s) {
        try { return Integer.valueOf(s); } catch (Exception e) { return null; }
    }

    private void close() {
        Stage s = (Stage) emailField.getScene().getWindow();
        s.close();
    }
}
