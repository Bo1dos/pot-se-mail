package ru.study.ui.fx.controller;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.Stage;
import ru.study.core.dto.AccountDTO;
import ru.study.core.event.NotificationEvent;
import ru.study.core.event.NotificationLevel;
import ru.study.core.event.NewMessageEvent;
import ru.study.core.event.bus.EventBus;
import ru.study.service.api.AccountService;
import ru.study.service.api.MailService;
import ru.study.service.api.MasterPasswordService;
import ru.study.service.api.SyncService;

import java.util.List;
import java.util.function.Consumer;

public class MainWindowController {

    @FXML public Label statusLabel;
    @FXML public Label bottomStatus;
    @FXML public ComboBox<AccountDTO> accountsCombo;

    private final MailService mailService;
    private final EventBus eventBus;
    private final AccountService accountService;
    private final MasterPasswordService masterPasswordService;
    private final SyncService syncService;

    // Added fields for controller linking
    private InboxController inboxController;
    private FoldersController foldersController;

    private final Consumer<NotificationEvent> notifHandler = this::onNotification;
    private final Consumer<NewMessageEvent> newMsgHandler = this::onNewMessage;

    public MainWindowController(MailService mailService,
                                EventBus eventBus,
                                AccountService accountService,
                                MasterPasswordService masterPasswordService,
                                SyncService syncService) {
        this.mailService = mailService;
        this.eventBus = eventBus;
        this.accountService = accountService;
        this.masterPasswordService = masterPasswordService;
        this.syncService = syncService;
    }

    @FXML
    public void initialize() {
        statusLabel.setText("Status: ready (MVP)");
        bottomStatus.setText("No account configured");

        eventBus.subscribe(NotificationEvent.class, notifHandler);
        eventBus.subscribe(NewMessageEvent.class, newMsgHandler);

        loadAccounts();
    }

    // ADDED: Setters for controller linking
    public void setInboxController(InboxController c) {
        this.inboxController = c;
        // если аккаунт уже выбран в combobox — сразу применим
        AccountDTO sel = accountsCombo == null ? null : accountsCombo.getSelectionModel().getSelectedItem();
        if (sel != null) {
            inboxController.setAccount(sel.id());
            // Set default folder
            inboxController.setFolder("INBOX");
        }
    }

    public void setFoldersController(FoldersController f) {
        this.foldersController = f;
        // связываем callback папок с inbox
        f.setOnFolderSelected((accId, folderName) -> {
            if (inboxController != null) {
                inboxController.setAccount(accId);
                inboxController.setFolder(folderName);
            }
        });
    }

    private void onNotification(NotificationEvent ev) {
        String txt = ev.message() == null ? "" : ev.message();
        NotificationLevel lvl = ev.level();
        Platform.runLater(() -> {
            switch (lvl) {
                case INFO -> statusLabel.setText("Info: " + txt);
                case SUCCESS -> statusLabel.setText("OK: " + txt);
                case ERROR -> {
                    statusLabel.setText("Error: " + txt);
                    bottomStatus.setText(txt);
                }
            }
        });
    }

    private void onNewMessage(NewMessageEvent ev) {
        Platform.runLater(() -> statusLabel.setText("New: " + (ev.getMessage() == null ? "" : ev.getMessage().subject())));
        // TODO: notify InboxController to refresh (via EventBus NewMessageEvent already handled there)
    }

    private void loadAccounts() {
        Platform.runLater(() -> {
            try {
                List<AccountDTO> list = accountService.listAccounts();
                accountsCombo.setItems(FXCollections.observableArrayList(list));
                accountsCombo.setCellFactory(lv -> new ListCell<>() {
                    @Override public void updateItem(AccountDTO item, boolean empty) {
                        super.updateItem(item, empty);
                        setText(empty || item == null ? null : item.email());
                    }
                });
                accountsCombo.setButtonCell(new ListCell<>() {
                    @Override public void updateItem(AccountDTO item, boolean empty) {
                        super.updateItem(item, empty);
                        setText(empty || item == null ? null : item.email());
                    }
                });
                if (!list.isEmpty()) accountsCombo.getSelectionModel().select(0);
                
                // ADDED: Listener for account selection to update inbox
                accountsCombo.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
                    if (newV != null && inboxController != null) {
                        inboxController.setAccount(newV.id());
                        // Set default folder when account changes
                        inboxController.setFolder("INBOX");
                    }
                });
                
                // If we have accounts and inboxController is set, initialize it
                if (!list.isEmpty() && inboxController != null) {
                    AccountDTO first = list.get(0);
                    inboxController.setAccount(first.id());
                    inboxController.setFolder("INBOX");
                }
            } catch (Exception e) {
                eventBus.publish(new NotificationEvent(NotificationLevel.ERROR, "Failed to load accounts: " + e.getMessage(), e));
            }
        });
    }

    @FXML
    public void onAddAccount() {
        try {
            // open AccountDialog modal
            javafx.fxml.FXMLLoader loader = new javafx.fxml.FXMLLoader(getClass().getResource("/fxml/AccountDialog.fxml"));
            loader.setControllerFactory(clazz -> {
                if (clazz == ru.study.ui.fx.controller.AccountDialogController.class) {
                    return new ru.study.ui.fx.controller.AccountDialogController(accountService, eventBus);
                }
                try { return clazz.getDeclaredConstructor().newInstance(); } catch (Exception ex) { throw new RuntimeException(ex); }
            });
            javafx.scene.Parent root = loader.load();
            Stage dlg = new Stage();
            dlg.initOwner(statusLabel.getScene().getWindow());
            dlg.setScene(new javafx.scene.Scene(root));
            dlg.setTitle("Add account");
            dlg.initModality(javafx.stage.Modality.APPLICATION_MODAL);
            dlg.showAndWait();
            // refresh accounts after dialog closed
            loadAccounts();
        } catch (Exception e) {
            eventBus.publish(new NotificationEvent(NotificationLevel.ERROR, "Failed to open Add account dialog: " + e.getMessage(), e));
        }
    }

    @FXML
    public void onSync() {
        AccountDTO selected = accountsCombo.getSelectionModel().getSelectedItem();
        if (selected == null) {
            eventBus.publish(new NotificationEvent(NotificationLevel.ERROR, "No account selected", null));
            return;
        }
        // start sync in background
        new Thread(() -> {
            try {
                syncService.syncAccount(selected.id());
                eventBus.publish(new NotificationEvent(NotificationLevel.SUCCESS, "Sync started for " + selected.email(), null));
            } catch (Exception e) {
                eventBus.publish(new NotificationEvent(NotificationLevel.ERROR, "Sync failed: " + e.getMessage(), e));
            }
        }, "sync-trigger").start();
    }

    @FXML
    public void onLogout() {
        // drop master password from memory if implementation supports it (we call changeMasterPassword with same pass -> noop)
        // safer: re-initialize app state — here we just show MasterPasswordDialog
        try {
            javafx.fxml.FXMLLoader loader = new javafx.fxml.FXMLLoader(getClass().getResource("/fxml/MasterPasswordDialog.fxml"));
            loader.setControllerFactory(clazz -> {
                if (clazz == ru.study.ui.fx.controller.MasterPasswordDialogController.class) {
                    return new ru.study.ui.fx.controller.MasterPasswordDialogController(masterPasswordService, eventBus);
                }
                try { return clazz.getDeclaredConstructor().newInstance(); } catch (Exception ex) { throw new RuntimeException(ex); }
            });
            javafx.scene.Parent root = loader.load();
            Stage dlg = new Stage();
            dlg.initOwner(statusLabel.getScene().getWindow());
            dlg.setScene(new javafx.scene.Scene(root));
            dlg.setTitle("Master Password");
            dlg.initModality(javafx.stage.Modality.APPLICATION_MODAL);
            dlg.showAndWait();
            // after dialog closed, maybe refresh accounts or state
            loadAccounts();
        } catch (Exception e) {
            eventBus.publish(new NotificationEvent(NotificationLevel.ERROR, "Failed to open Master Password dialog: " + e.getMessage(), e));
        }
    }
}