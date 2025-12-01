package ru.study.ui.fx.controller;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import javafx.scene.web.HTMLEditor;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import ru.study.core.event.NotificationEvent;
import ru.study.core.event.NotificationLevel;
import ru.study.core.event.bus.EventBus;
import ru.study.core.event.NewMessageEvent;
import ru.study.core.dto.MessageSummaryDTO;
import ru.study.service.api.AccountService;
import ru.study.service.api.MailService;
import ru.study.service.dto.OutgoingAttachmentDTO;
import ru.study.service.dto.SendMessageDTO;
import ru.study.service.dto.SendResultDTO;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;

/**
 * ComposerController — constructor-injected services.
 * FXML: Composer.fxml
 */
public class ComposerController {

    private final MailService mailService;
    private final EventBus eventBus;
    // accountService может понадобиться в будущем для выбора реального аккаунта
    @SuppressWarnings("unused")
    private final AccountService accountService;

    // attachments stored in DTOs; UI does not keep InputStream open — mailService will handle filePath
    private final List<OutgoingAttachmentDTO> attachments = new ArrayList<>();

    public ComposerController(MailService mailService, EventBus eventBus, AccountService accountService) {
        this.mailService = mailService;
        this.eventBus = eventBus;
        this.accountService = accountService;
    }

    @FXML public TextField toField;
    @FXML public TextField subjectField;
    @FXML public HTMLEditor htmlEditor;

    @FXML public ComboBox<ru.study.core.dto.AccountDTO> fromCombo;

    @FXML
    public void initialize() {
        // existing init...
        List<ru.study.core.dto.AccountDTO> accounts = accountService.listAccounts();
        if (accounts != null && !accounts.isEmpty()) {
            fromCombo.getItems().setAll(accounts);
            fromCombo.setConverter(new javafx.util.StringConverter<>() {
                @Override public String toString(ru.study.core.dto.AccountDTO a) { return a == null ? "" : a.email(); }
                @Override public ru.study.core.dto.AccountDTO fromString(String s) { return null; }
            });
            fromCombo.getSelectionModel().selectFirst();
        }
    }

    // optional: bind this to Attach button's onAction in FXML if you add it
    @FXML
    public void onAttach() {
        Window w = toField.getScene().getWindow();
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select attachment");
        File f = chooser.showOpenDialog(w);
        if (f == null) return;
        OutgoingAttachmentDTO dto = OutgoingAttachmentDTO.builder()
                .fileName(f.getName())
                .contentType(null)
                .input(null)
                .filePath(f.getAbsolutePath())
                .build();
        attachments.add(dto);
        eventBus.publish(new NotificationEvent(NotificationLevel.INFO, "Attached: " + f.getName(), null));
    }

    @FXML
    public void onSend() {
        String to = toField.getText();
        if (to == null || to.isBlank()) {
            eventBus.publish(new NotificationEvent(NotificationLevel.ERROR, "Recipient is required", null));
            return;
        }

        // pick real account: use first available account (MVP). Better: let user choose in UI.
        List<ru.study.core.dto.AccountDTO> accounts = accountService.listAccounts();
        if (accounts == null || accounts.isEmpty()) {
            eventBus.publish(new NotificationEvent(NotificationLevel.ERROR, "No configured account. Add an account first.", null));
            return;
        }
        ru.study.core.dto.AccountDTO acc = fromCombo.getSelectionModel().getSelectedItem();
        Long accountId = acc.id();
        String fromAddress = acc.email();

        // Build DTO (simple parsing: comma separated)
        List<String> toList = List.of(to.split("\\s*,\\s*"));
        SendMessageDTO dto = SendMessageDTO.builder()
                .from(fromAddress)
                .to(toList)
                .cc(null)
                .bcc(null)
                .subject(subjectField.getText())
                .body(htmlEditor.getHtmlText())
                .html(true)
                .attachments(List.copyOf(attachments))
                .build();

        eventBus.publish(new NotificationEvent(NotificationLevel.INFO, "Sending...", null));

        // call async send using the actual accountId
        mailService.sendAsync(dto, accountId, false, false)
            .whenComplete((res, ex) -> {
                Platform.runLater(() -> {
                    if (ex != null) {
                        Throwable cause = ex instanceof CompletionException ? ex.getCause() : ex;
                        eventBus.publish(new NotificationEvent(NotificationLevel.ERROR, "Send failed: " + cause.getMessage(), cause));
                    } else {
                        SendResultDTO r = res;
                        if (r.isSuccess()) {
                            eventBus.publish(new NotificationEvent(NotificationLevel.SUCCESS, "Sent: " + r.getMessageId(), null));
                            MessageSummaryDTO summary = new MessageSummaryDTO(-1L, dto.getFrom(), dto.getSubject(), "", java.time.Instant.now(), true, false, !dto.getAttachments().isEmpty());
                            eventBus.publish(new NewMessageEvent(summary));
                            subjectField.clear();
                            htmlEditor.setHtmlText("");
                            attachments.clear();
                        } else {
                            eventBus.publish(new NotificationEvent(NotificationLevel.ERROR, "Send failed: " + r.getError(), null));
                        }
                    }
                });
            });
    }


}