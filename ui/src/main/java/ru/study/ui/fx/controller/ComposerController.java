package ru.study.ui.fx.controller;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import javafx.scene.web.HTMLEditor;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import ru.study.core.dto.AccountDTO;
import ru.study.core.dto.MessageDetailDTO;
import ru.study.core.dto.MessageSummaryDTO;
import ru.study.core.event.ComposeMessageEvent;
import ru.study.core.event.NotificationEvent;
import ru.study.core.event.NotificationLevel;
import ru.study.core.event.bus.EventBus;
import ru.study.core.event.NewMessageEvent;
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
 * Keeps UI logic simple: fill fields, attach files, send.
 */
public class ComposerController {

    private final MailService mailService;
    private final EventBus eventBus;
    private final AccountService accountService;

    // attachments are represented by DTOs (filePath used by service)
    private final List<OutgoingAttachmentDTO> attachments = new ArrayList<>();

    public ComposerController(MailService mailService, EventBus eventBus, AccountService accountService) {
        this.mailService = mailService;
        this.eventBus = eventBus;
        this.accountService = accountService;
    }

    @FXML public TextField toField;
    @FXML public TextField subjectField;
    @FXML public HTMLEditor htmlEditor;
    @FXML public ComboBox<AccountDTO> fromCombo;


    @FXML public CheckBox encryptCheckbox;
    @FXML public CheckBox signCheckbox;

    @FXML
    public void initialize() {
        // load accounts in background to avoid blocking UI
        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                return accountService.listAccounts();
            } catch (Exception e) {
                return List.<AccountDTO>of();
            }
        }).thenAccept(list -> {
            Platform.runLater(() -> {
                if (list != null && !list.isEmpty()) {
                    fromCombo.getItems().setAll(list);
                    fromCombo.setConverter(new javafx.util.StringConverter<>() {
                        @Override public String toString(AccountDTO a) { return a == null ? "" : a.email(); }
                        @Override public AccountDTO fromString(String s) { return null; }
                    });
                    fromCombo.getSelectionModel().selectFirst();
                }
            });
        });

        // subscribe to ComposeMessageEvent (reply/forward requests)
        eventBus.subscribe(ComposeMessageEvent.class, ev -> {
            Platform.runLater(() -> {
                try {
                    MessageDetailDTO orig = ev.getOriginalMessage();
                    if (orig == null) {
                        // new message
                        toField.clear();
                        subjectField.clear();
                        htmlEditor.setHtmlText("");
                    } else {
                        // try to extract useful fields with safe access (we don't do heavy reflection here)
                        String fromAddr = safeGetString(() -> {
                            try { return (String) MessageDetailDTO.class.getMethod("from").invoke(orig); } catch (Exception ex) { return null; }
                        });

                        String subj = safeGetString(() -> {
                            try { return (String) MessageDetailDTO.class.getMethod("subject").invoke(orig); } catch (Exception ex) { return null; }
                        });

                        String bodyHtml = safeGetString(() -> {
                            // common method names we try in order
                            try { return (String) MessageDetailDTO.class.getMethod("bodyHtml").invoke(orig); } catch (Exception ignored) {}
                            try { return (String) MessageDetailDTO.class.getMethod("getBodyHtml").invoke(orig); } catch (Exception ignored) {}
                            try { return (String) MessageDetailDTO.class.getMethod("body").invoke(orig); } catch (Exception ignored) {}
                            try { return (String) MessageDetailDTO.class.getMethod("text").invoke(orig); } catch (Exception ignored) {}
                            return null;
                        });

                        if (fromAddr != null && !fromAddr.isBlank()) toField.setText(fromAddr);
                        if (subj == null) subj = "";
                        if (!subj.toLowerCase().startsWith("re:")) subj = "Re: " + subj;
                        subjectField.setText(subj);

                        // If original body is HTML, use it; else use quoted plaintext.
                        if (bodyHtml != null && !bodyHtml.isBlank()) {
                            // simple quoted wrapper
                            String quoted = "<div style='border-left:2px solid #ccc; padding-left:8px; color:#555;'>" +
                                    bodyHtml + "</div><br/>";
                            htmlEditor.setHtmlText(quoted);
                        } else {
                            htmlEditor.setHtmlText("<pre>(original message omitted)</pre>");
                        }
                    }
                } catch (Throwable t) {
                    eventBus.publish(new NotificationEvent(NotificationLevel.ERROR, "Failed to prepare composer: " + t.getMessage(), t));
                }
            });
        });
    }

    /** Attach file via FileChooser */
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

    /** Send message (async) */
    @FXML
    public void onSend() {
        String to = toField.getText();
        if (to == null || to.isBlank()) {
            eventBus.publish(new NotificationEvent(NotificationLevel.ERROR, "Recipient is required", null));
            return;
        }

        AccountDTO acc = fromCombo.getSelectionModel().getSelectedItem();
        if (acc == null) {
            eventBus.publish(new NotificationEvent(NotificationLevel.ERROR, "No account selected", null));
            return;
        }

        Long accountId = acc.id();
        String fromAddress = acc.email();

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

        boolean encrypt = encryptCheckbox != null && encryptCheckbox.isSelected();
        boolean sign = signCheckbox != null && signCheckbox.isSelected();

        System.out.println("Encrypt and sign:" + encrypt + " " + sign);

        mailService.sendAsync(dto, accountId, encrypt, sign)
                .whenComplete((res, ex) -> {
                    Platform.runLater(() -> {
                        if (ex != null) {
                            Throwable cause = ex instanceof CompletionException ? ex.getCause() : ex;
                            eventBus.publish(new NotificationEvent(NotificationLevel.ERROR, "Send failed: " + (cause == null ? ex.getMessage() : cause.getMessage()), cause));
                        } else {
                            SendResultDTO r = res;
                            if (r.isSuccess()) {
                                eventBus.publish(new NotificationEvent(NotificationLevel.SUCCESS, "Sent: " + r.getMessageId(), null));
                                // publish NewMessageEvent to allow mailbox to refresh / show sent msg if desired
                                var summary = new MessageSummaryDTO(-1L, dto.getFrom(), dto.getSubject(), "", java.time.Instant.now(), true, false, !dto.getAttachments().isEmpty());
                                eventBus.publish(new NewMessageEvent(summary));
                                // clear UI
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

    // Helper: safe supplier wrapper
    private static String safeGetString(java.util.concurrent.Callable<String> c) {
        try {
            return c.call();
        } catch (Exception e) {
            return null;
        }
    }
}
