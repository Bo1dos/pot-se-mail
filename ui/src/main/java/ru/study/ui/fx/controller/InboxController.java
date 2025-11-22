package ru.study.ui.fx.controller;

import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.TableView;
import javafx.scene.control.TableColumn;
import javafx.scene.control.cell.PropertyValueFactory;

public class InboxController {

    @FXML public TableView<EmailRow> inboxTable;

    @FXML
    public void initialize() {
        TableColumn<EmailRow, String> fromCol = new TableColumn<>("From");
        fromCol.setCellValueFactory(new PropertyValueFactory<>("from"));
        TableColumn<EmailRow, String> subjCol = new TableColumn<>("Subject");
        subjCol.setCellValueFactory(new PropertyValueFactory<>("subject"));
        TableColumn<EmailRow, String> dateCol = new TableColumn<>("Date");
        dateCol.setCellValueFactory(new PropertyValueFactory<>("date"));

        inboxTable.getColumns().setAll(fromCol, subjCol, dateCol);

        inboxTable.setItems(FXCollections.observableArrayList(
            new EmailRow("alice@example.com","Hello","2025-11-16"),
            new EmailRow("bob@example.com","Meeting notes","2025-11-15")
        ));
    }

    public static class EmailRow {
        private final String from;
        private final String subject;
        private final String date;
        public EmailRow(String from, String subject, String date) {
            this.from = from; this.subject = subject; this.date = date;
        }
        public String getFrom() { return from; }
        public String getSubject() { return subject; }
        public String getDate() { return date; }
    }
}
