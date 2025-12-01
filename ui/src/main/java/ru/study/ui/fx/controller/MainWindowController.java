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
    private MessageViewController messageViewController;

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

        // Устанавливаем callback на выбор сообщения из inbox
        // он будет загружать MessageDetailDTO через mailService.getMessage(...) в фоне и показывать HTML в MessageViewController
        inboxController.setOnMessageSelected(messageId -> {
            if (messageId == null) return;

            // Получаем текущий выбранный аккаунт id из combobox (MainWindow всегда владеет этим контролом)
            AccountDTO selected = accountsCombo.getSelectionModel().getSelectedItem();
            if (selected == null) {
                eventBus.publish(new NotificationEvent(NotificationLevel.ERROR, "No account selected to load message", null));
                return;
            }
            Long accountId = selected.id();

            // safety: need messageViewController to be present
            if (messageViewController == null) {
                eventBus.publish(new NotificationEvent(NotificationLevel.ERROR, "Message view not available", null));
                return;
            }

            // Загрузка в фоне
            java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    // mailService.getMessage может бросать CoreException
                    return mailService.getMessage(accountId, messageId);
                } catch (Exception ex) {
                    // оборачиваем исключение для thenAccept блоков
                    throw new RuntimeException(ex);
                }
            }).thenAccept(detail -> {
                // detail может быть null — покажем соответствующий текст
                Platform.runLater(() -> {
                    try {
                        if (detail == null) {
                            messageViewController.showHtml("<p>(no content)</p>");
                            eventBus.publish(new NotificationEvent(NotificationLevel.INFO, "Message not found", null));
                            return;
                        }

                        String html = null;

                        // Список candidate-методов/полей, которые чаще всего встречаются
                        String[] candidates = new String[] {
                            "bodyHtml", "getBodyHtml",
                            "html", "getHtml",
                            "body", "getBody",
                            "text", "getText",
                            "bodyText", "getBodyText",
                            "htmlBody", "getHtmlBody"
                        };

                        // Попробуем вызвать методы
                        for (String name : candidates) {
                            if (html != null && !html.isBlank()) break;
                            try {
                                // сначала пытаемся как метод без аргументов
                                java.lang.reflect.Method m = detail.getClass().getMethod(name);
                                if (m != null) {
                                    Object val = m.invoke(detail);
                                    if (val instanceof String) {
                                        html = (String) val;
                                        break;
                                    }
                                }
                            } catch (NoSuchMethodException ignore) {
                                // метод отсутствует — пробуем поле ниже
                            } catch (Exception ex) {
                                // какое-то исключение при вызове — логним и продолжаем
                                eventBus.publish(new NotificationEvent(NotificationLevel.ERROR, "Failed to call " + name + "(): " + ex.getMessage(), ex));
                            }

                            // если метода нет — пробуем как поле (record-поля тоже видны как get-методы, но на всякий)
                            try {
                                java.lang.reflect.Field f = detail.getClass().getDeclaredField(name);
                                f.setAccessible(true);
                                Object val = f.get(detail);
                                if (val instanceof String) {
                                    html = (String) val;
                                    break;
                                }
                            } catch (NoSuchFieldException ignore) {
                            } catch (Exception ex) {
                                eventBus.publish(new NotificationEvent(NotificationLevel.ERROR, "Failed to read field " + name + ": " + ex.getMessage(), ex));
                            }
                        }

                        // Fallbacks: если html пустой — попробуем взять plain text поле, или subject/snippet из DTO
                        if (html == null || html.isBlank()) {
                            // попробуем искать subject или snippet поля, чтобы показать хоть что-то
                            String fallback = null;
                            try {
                                // common getters: subject(), getSubject()
                                try {
                                    java.lang.reflect.Method msub = detail.getClass().getMethod("subject");
                                    Object v = msub.invoke(detail);
                                    if (v instanceof String) fallback = (String) v;
                                } catch (NoSuchMethodException ignore) {
                                    try {
                                        java.lang.reflect.Method msub2 = detail.getClass().getMethod("getSubject");
                                        Object v2 = msub2.invoke(detail);
                                        if (v2 instanceof String) fallback = (String) v2;
                                    } catch (NoSuchMethodException ignore2) {}
                                }
                            } catch (Exception ex) {
                                // ignore
                            }

                            if (fallback == null || fallback.isBlank()) {
                                // последний вариант — toString()
                                fallback = detail.toString();
                            }

                            // Показываем fallback в простой обёртке
                            String wrapped = "<html><body><pre>" + escapeHtml(fallback) + "</pre></body></html>";
                            messageViewController.showHtml(wrapped);
                            eventBus.publish(new NotificationEvent(NotificationLevel.INFO, "Shown fallback content (no HTML). length=" + wrapped.length(), null));
                        } else {
                            // Если нашли HTML — покажем напрямую
                            messageViewController.showHtml(html);
                            eventBus.publish(new NotificationEvent(NotificationLevel.INFO, "Shown HTML content, len=" + (html == null ? 0 : html.length()), null));
                        }

                    } catch (Exception uiEx) {
                        eventBus.publish(new NotificationEvent(NotificationLevel.ERROR, "Failed to render message: " + uiEx.getMessage(), uiEx));
                        messageViewController.showHtml("<p>(render error)</p>");
                    }
                });
            }).exceptionally(ex -> {
                Throwable cause = ex instanceof java.util.concurrent.CompletionException ? ex.getCause() : ex;
                eventBus.publish(new NotificationEvent(NotificationLevel.ERROR, "Failed to load message: " + cause.getMessage(), cause));
                return null;
            });
        });
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

    public void setMessageViewController(MessageViewController mvc) {
        this.messageViewController = mvc;
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

    // Вспомогательный метод для экранирования HTML
    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}