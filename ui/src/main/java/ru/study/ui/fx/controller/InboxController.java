package ru.study.ui.fx.controller;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.TableView;
import javafx.scene.control.TableColumn;
import javafx.scene.control.cell.PropertyValueFactory;
import ru.study.core.dto.MessageSummaryDTO;
import ru.study.core.event.NewMessageEvent;
import ru.study.core.event.SyncCompletedEvent;
import ru.study.core.event.bus.EventBus;
import ru.study.service.api.MailService;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * InboxController — fills TableView from MailService.listMessages.
 * Simple MVP: uses accountId = 1L by default (adjust later).
 */
public class InboxController {

    @FXML public TableView<EmailRow> inboxTable;

    private final MailService mailService;
    private final EventBus eventBus;

    private final Consumer<NewMessageEvent> newMsgHandler = ev -> refreshInbox();

    // callback to notify parent (MainWindowController) when a message row is selected
    private Consumer<Long> onMessageSelected = id -> {};

    private Long currentAccountId = null;
    private String currentFolder = "INBOX";




    public InboxController(MailService mailService, EventBus eventBus) {
        this.mailService = mailService;
        this.eventBus = eventBus;
    }

    @FXML
    public void initialize() {
        TableColumn<EmailRow, String> fromCol = new TableColumn<>("From");
        fromCol.setCellValueFactory(new PropertyValueFactory<>("from"));
        TableColumn<EmailRow, String> subjCol = new TableColumn<>("Subject");
        subjCol.setCellValueFactory(new PropertyValueFactory<>("subject"));
        TableColumn<EmailRow, String> dateCol = new TableColumn<>("Date");
        dateCol.setCellValueFactory(new PropertyValueFactory<>("date"));

        inboxTable.getColumns().setAll(fromCol, subjCol, dateCol);

        // subscribe to new message events
        eventBus.subscribe(NewMessageEvent.class, newMsgHandler);

        // subscribe to sync completed events: refresh inbox for current account
        eventBus.subscribe(SyncCompletedEvent.class, ev -> {
            if (currentAccountId != null && ev.getAccountId() != null && ev.getAccountId().equals(currentAccountId)) {
                refreshInbox();
            }
        });

        // selection listener ...
        inboxTable.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
            if (newV != null && onMessageSelected != null) {
                onMessageSelected.accept(newV.getId());
            }
        });

        // initial load (only if account selected)
        // don't call refreshInbox() if currentAccountId is null (we will be set by FoldersController interaction)
        if (currentAccountId != null) refreshInbox();
    }

    public void refreshInbox() {
        if (currentAccountId == null) {
            // nothing to show — clear
            Platform.runLater(() -> inboxTable.setItems(FXCollections.observableArrayList()));
            return;
        }
        
        // avoid blocking UI — run in background
        CompletableFuture.supplyAsync(() -> {
            try {
                // TODO: pick accountId and folder dynamically; using 1L and "INBOX" for MVP
                List<MessageSummaryDTO> msgs = mailService.listMessages(
                    currentAccountId,
                    currentFolder,
                    0, 50
                );
                return msgs;
            } catch (Exception e) {
                // publish notification to UI
                eventBus.publish(new ru.study.core.event.NotificationEvent(ru.study.core.event.NotificationLevel.ERROR, "Failed to load inbox: " + e.getMessage(), e));
                return List.<MessageSummaryDTO>of();
            }
        }).thenAccept(list -> {
            Platform.runLater(() -> {
                var rows = list.stream()
                        .map(m -> new EmailRow(m.id(), m.from(), m.subject(), m.date() == null ? "" : m.date().toString()))
                        .toList();
                inboxTable.setItems(FXCollections.observableArrayList(rows));
            });
        });
    }

    // setter for parent to receive selection notifications
    public void setOnMessageSelected(Consumer<Long> handler) {
        if (handler != null) this.onMessageSelected = handler;
    }

    public static class EmailRow {
        private final Long id;
        private final String from;
        private final String subject;
        private final String date;
        public EmailRow(Long id, String from, String subject, String date) { this.id = id; this.from = from; this.subject = subject; this.date = date; }
        public Long getId() { return id; }
        public String getFrom() { return from; }
        public String getSubject() { return subject; }
        public String getDate() { return date; }
    }


    public void setAccount(Long id) {
        this.currentAccountId = id;
        refreshInbox();
    }

    public void setFolder(String f) {
        this.currentFolder = f;
        refreshInbox();
    }
}
