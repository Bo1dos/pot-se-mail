package ru.study.ui.fx.controller;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import ru.study.core.dto.MessageSummaryDTO;
import ru.study.core.event.NotificationEvent;
import ru.study.core.event.NotificationLevel;
import ru.study.core.event.bus.EventBus;
import ru.study.service.api.MailService;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;

public class InboxController {

    @FXML public TableView<MessageSummaryDTO> inboxTable;

    private final MailService mailService;
    private final EventBus eventBus;

    private Long currentAccountId;
    private String currentFolder = "INBOX";
    private final ObservableList<MessageSummaryDTO> items = FXCollections.observableArrayList();
    private Consumer<Long> onMessageSelected;

    // page/size defaults for listMessages call
    private static final int DEFAULT_PAGE = 0;
    private static final int DEFAULT_SIZE = 200;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public InboxController(MailService mailService, EventBus eventBus) {
        this.mailService = mailService;
        this.eventBus = eventBus;
    }

    @FXML
    public void initialize() {
        // Если колонки из FXML уже есть — назначаем cellValueFactory для них
        if (!inboxTable.getColumns().isEmpty()) {
            try {
                // Column 0 -> From
                TableColumn<?, ?> c0 = inboxTable.getColumns().get(0);
                ((TableColumn<MessageSummaryDTO, String>) c0).setCellValueFactory(cd ->
                        new javafx.beans.property.SimpleStringProperty(cd.getValue() == null ? "" : safeString(cd.getValue().from()))
                );

                // Column 1 -> Subject
                TableColumn<?, ?> c1 = inboxTable.getColumns().get(1);
                ((TableColumn<MessageSummaryDTO, String>) c1).setCellValueFactory(cd ->
                        new javafx.beans.property.SimpleStringProperty(cd.getValue() == null ? "" : safeString(cd.getValue().subject()))
                );

                // Column 2 -> Date
                TableColumn<?, ?> c2 = inboxTable.getColumns().get(2);
                ((TableColumn<MessageSummaryDTO, String>) c2).setCellValueFactory(cd -> {
                    MessageSummaryDTO v = cd.getValue();
                    if (v == null || v.date() == null) return new javafx.beans.property.SimpleStringProperty("");
                    try {
                        String s = DATE_FMT.format(v.date().atZone(ZoneId.systemDefault()).toLocalDateTime());
                        return new javafx.beans.property.SimpleStringProperty(s);
                    } catch (Exception ex) {
                        return new javafx.beans.property.SimpleStringProperty(v.date().toString());
                    }
                });
            } catch (Exception e) {
                // на всякий: если структура колонок нестандартная — создадим наши колонки ниже
                inboxTable.getColumns().clear();
            }
        }

        // если после попытки выше колонки пусты — создаём их программно
        if (inboxTable.getColumns().isEmpty()) {
            TableColumn<MessageSummaryDTO, String> fromCol = new TableColumn<>("From");
            fromCol.setPrefWidth(140);
            fromCol.setCellValueFactory(cd -> new javafx.beans.property.SimpleStringProperty(cd.getValue() == null ? "" : safeString(cd.getValue().from())));

            TableColumn<MessageSummaryDTO, String> subjCol = new TableColumn<>("Subject");
            subjCol.setPrefWidth(240);
            subjCol.setCellValueFactory(cd -> new javafx.beans.property.SimpleStringProperty(cd.getValue() == null ? "" : safeString(cd.getValue().subject())));

            TableColumn<MessageSummaryDTO, String> dateCol = new TableColumn<>("Date");
            dateCol.setPrefWidth(120);
            dateCol.setCellValueFactory(cd -> {
                if (cd.getValue() == null || cd.getValue().date() == null) return new javafx.beans.property.SimpleStringProperty("");
                try {
                    String s = DATE_FMT.format(cd.getValue().date().atZone(ZoneId.systemDefault()).toLocalDateTime());
                    return new javafx.beans.property.SimpleStringProperty(s);
                } catch (Exception e) {
                    return new javafx.beans.property.SimpleStringProperty(cd.getValue().date().toString());
                }
            });

            inboxTable.getColumns().addAll(fromCol, subjCol, dateCol);
        }

        inboxTable.setItems(items);

        // style строк: выделяем непрочитанные (seen==false) жирным шрифтом
        inboxTable.setRowFactory(tv -> new TableRow<>() {
            @Override
            protected void updateItem(MessageSummaryDTO item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setStyle("");
                } else {
                    if (!item.seen()) {
                        // unread — bold
                        setStyle("-fx-font-weight: bold;");
                    } else {
                        setStyle("");
                    }
                }
            }
        });

        // selection -> callback with id
        inboxTable.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
            if (onMessageSelected != null) {
                onMessageSelected.accept(newV == null ? null : newV.id());
            }
        });

        // double click -> also trigger selection callback (UX)
        inboxTable.setOnMouseClicked(ev -> {
            if (ev.getButton() == MouseButton.PRIMARY && ev.getClickCount() == 2) {
                MessageSummaryDTO sel = inboxTable.getSelectionModel().getSelectedItem();
                if (sel != null && onMessageSelected != null) onMessageSelected.accept(sel.id());
            }
        });
    }

    // --- external API used by MainWindowController ---
    public void setOnMessageSelected(Consumer<Long> cb) {
        this.onMessageSelected = cb;
    }

    public Long getSelectedMessageId() {
        MessageSummaryDTO sel = inboxTable.getSelectionModel().getSelectedItem();
        return sel == null ? null : sel.id();
    }

    public void setAccount(Long accountId) {
        this.currentAccountId = accountId;
        refresh();
    }

    public void setFolder(String folder) {
        this.currentFolder = folder == null ? "INBOX" : folder;
        refresh();
    }

    public void refresh() {
        if (currentAccountId == null) {
            items.clear();
            return;
        }

        // background load
        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                List<MessageSummaryDTO> list = mailService.listMessages(currentAccountId, currentFolder, DEFAULT_PAGE, DEFAULT_SIZE);
                return list;
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }).thenAccept(list -> {
            Platform.runLater(() -> {
                items.setAll(list == null ? List.of() : list);
            });
        }).exceptionally(ex -> {
            Throwable cause = ex instanceof java.util.concurrent.CompletionException ? ex.getCause() : ex;
            eventBus.publish(new NotificationEvent(NotificationLevel.ERROR, "Failed to load messages: " + (cause == null ? ex.getMessage() : cause.getMessage()), cause));
            return null;
        });
    }

    /**
     * Locally mark message as seen (updates the table row style by replacing the record).
     * Does not automatically sync to server — MainWindowController may call mailService.markMessageSeen if available.
     */
    public void markAsSeen(Long messageId) {
        if (messageId == null) return;
        Platform.runLater(() -> {
            for (int i = 0; i < items.size(); i++) {
                MessageSummaryDTO s = items.get(i);
                if (s != null && messageId.equals(s.id()) && !s.seen()) {
                    // replace with new record where seen=true (record constructor order)
                    MessageSummaryDTO replaced = new MessageSummaryDTO(
                            s.id(), s.from(), s.subject(), s.snippet(), s.date(), true, s.encrypted(), s.hasAttachments()
                    );
                    items.set(i, replaced);
                    // ensure row style applied (force refresh)
                    inboxTable.refresh();
                    break;
                }
            }
        });
    }

    private static String safeString(String s) {
        return s == null ? "" : s;
    }
}
