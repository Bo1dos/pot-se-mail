package ru.study.ui.fx.controller;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.AnchorPane;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.study.core.dto.AttachmentMetaDTO;
import ru.study.core.dto.MessageDetailDTO;
import ru.study.core.event.NotificationEvent;
import ru.study.core.event.NotificationLevel;
import ru.study.core.event.bus.EventBus;
import ru.study.service.api.AttachmentService;
import ru.study.service.api.MailService;
import ru.study.service.api.MasterPasswordService;

import java.awt.Desktop;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class MessageViewController {

    private static final Logger logger = LoggerFactory.getLogger(MessageViewController.class);

    @FXML public Label subjectLabel;
    @FXML public Label fromLabel;
    @FXML public Label toLabel;
    @FXML public Label dateLabel;

    // Заменили WebView на контейнер
    @FXML public AnchorPane webViewContainer;
    @FXML public TextArea plainTextArea;

    @FXML public ListView<AttachmentMetaDTO> attachmentsList;
    @FXML public Button openAttachmentBtn;
    @FXML public Button saveAttachmentBtn;

    // Теперь WebView создаём вручную
    private WebView webView;
    private WebEngine webEngine;

    private final MailService mailService;
    private final AttachmentService attachmentService;
    private final MasterPasswordService masterPasswordService;
    private final EventBus eventBus;

    // текущий контекст (для вызовов getMessage)
    private Long accountId;
    private Long messageId;
    private MessageDetailDTO current;

    public MessageViewController(MailService mailService,
                                 AttachmentService attachmentService,
                                 MasterPasswordService masterPasswordService,
                                 EventBus eventBus) {
        this.mailService = mailService;
        this.attachmentService = attachmentService;
        this.masterPasswordService = masterPasswordService;
        this.eventBus = eventBus;
        
        logger.debug("MessageViewController created with mailService: {}, attachmentService: {}, masterPasswordService: {}, eventBus: {}",
                mailService != null, attachmentService != null, masterPasswordService != null, eventBus != null);
    }

    @FXML
    public void initialize() {
        logger.debug("Initializing MessageViewController UI components");
        
        // Создаём WebView программно — это надёжно независимо от classloader'ов
        webView = new WebView();
        webEngine = webView.getEngine();
        webEngine.setJavaScriptEnabled(true); // по желанию
        logger.debug("WebView created and WebEngine initialized");

        // Зафиксировать резайз: растянем WebView по контейнеру
        AnchorPane.setTopAnchor(webView, 0.0);
        AnchorPane.setBottomAnchor(webView, 0.0);
        AnchorPane.setLeftAnchor(webView, 0.0);
        AnchorPane.setRightAnchor(webView, 0.0);

        webViewContainer.getChildren().add(webView);
        logger.debug("WebView added to container with anchors set");

        // остальная инициализация
        plainTextArea.setEditable(false);
        plainTextArea.setVisible(false);
        webViewContainer.setVisible(false);

        // attachments ListView cell factory
        attachmentsList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(AttachmentMetaDTO it, boolean empty) {
                super.updateItem(it, empty);
                if (empty || it == null) {
                    setText(null);
                } else {
                    String s = it.fileName() + (it.size() != null ? " (" + it.size() + " bytes)" : "");
                    setText(s);
                }
            }
        });

        attachmentsList.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
            boolean has = newV != null;
            openAttachmentBtn.setDisable(!has);
            saveAttachmentBtn.setDisable(!has);
            
            if (newV != null) {
                logger.trace("Attachment selected: {}", newV.fileName());
            }
        });

        openAttachmentBtn.setOnAction(e -> openSelectedAttachment());
        saveAttachmentBtn.setOnAction(e -> saveSelectedAttachment());
        
        logger.info("MessageViewController initialized successfully with programmatic WebView");
    }

    /**
     * Set account context for the view.
     */
    public void setAccount(Long accountId) {
        logger.debug("Setting account context: {}", accountId);
        this.accountId = accountId;
    }

    /**
     * Ask view to load and show message with given id (background).
     */
    public void setMessage(Long messageId) {
        // TODO: убрать, дебаг
        System.out.println("MessageViewController.setMessage called: account=" + accountId + " messageId=" + messageId);
        if (messageId == null) {
            // clear UI
            this.messageId = null;
            current = null;
            Platform.runLater(() -> {
                subjectLabel.setText("");
                fromLabel.setText("");
                toLabel.setText("");
                dateLabel.setText("");
                plainTextArea.clear();
                webEngine.loadContent("<html><body><i>(no message)</i></body></html>");
                webViewContainer.setVisible(true);
                attachmentsList.getItems().clear();
            });
            return;
        }
        if (this.messageId != null && this.messageId.equals(messageId) && this.accountId != null && this.accountId.equals(accountId)) {
            logger.debug("Same message already loaded, ignoring");
            return;
        }
        this.messageId = messageId;
        // existing load...
        loadAndDisplay(accountId, messageId);
    }


    /**
     * Force reload current message.
     */
    public void refresh() {
        logger.debug("Refreshing current message");
        if (accountId != null && messageId != null) {
            loadAndDisplay(accountId, messageId);
        } else {
            logger.warn("Cannot refresh - missing accountId: {} or messageId: {}", accountId, messageId);
        }
    }

    private void loadAndDisplay(Long accountId, Long messageId) {
        logger.info("Loading message {} for account {}", messageId, accountId);
        
        // clear UI quickly
        Platform.runLater(() -> {
            subjectLabel.setText("Loading...");
            fromLabel.setText("");
            toLabel.setText("");
            dateLabel.setText("");
            plainTextArea.clear();
            plainTextArea.setVisible(false);
            webEngine.loadContent("<html><body>Loading...</body></html>");
            webViewContainer.setVisible(true);
            attachmentsList.getItems().clear();
            openAttachmentBtn.setDisable(true);
            saveAttachmentBtn.setDisable(true);
        });

        CompletableFuture.supplyAsync(() -> {
            try {
                logger.debug("Calling mailService.getMessage for account {}, message {}", accountId, messageId);
                MessageDetailDTO dto = mailService.getMessage(accountId, messageId);
                // TODO: убрать, дебаг
                // System.out.println("DTO: htmlBody()=" + dto.bodyHtml() + ", bodyText()=" + dto.bodyText() + ", isEncrypted? " + dto.encrypted() + ", attachments=" + (dto.attachments()==null?0:dto.attachments().size()));
                logger.debug("Successfully retrieved message DTO for message {}", messageId);
                return dto;
            } catch (Exception e) {
                logger.error("Failed to load message {} for account {}", messageId, accountId, e);
                throw new RuntimeException(e);
            }
        }).thenAccept(dto -> {
            logger.debug("Message DTO received, updating UI for message {}", messageId);
            current = dto;
            Platform.runLater(() -> displayMessage(dto));
        }).exceptionally(ex -> {
            Throwable c = ex instanceof java.util.concurrent.CompletionException ? ex.getCause() : ex;
            logger.error("Exception during message loading for message {}", messageId, c);
            eventBus.publish(new NotificationEvent(NotificationLevel.ERROR, "Failed to load message: " + c.getMessage(), c));
            Platform.runLater(() -> {
                subjectLabel.setText("Failed to load message");
                webEngine.loadContent("<html><body><i>Failed to load message.</i></body></html>");
                webViewContainer.setVisible(true);
            });
            return null;
        });
    }

    private void displayMessage(MessageDetailDTO dto) {
        logger.debug("Displaying message in UI");
        
        // NOTE: DTO accessors assumed: subject(), from(), to(), sentAt(), htmlBody(), body(), attachments()
        subjectLabel.setText(safeStr(dto.subject()));
        fromLabel.setText(safeStr(dto.from()));
        toLabel.setText(safeStr(dto.to()));
        dateLabel.setText(dto.date() == null ? "" : dto.date().toString());

        String html = dto.bodyHtml();   // record accessor
        String body = dto.bodyText();   // record accessor

        if (html != null && !html.isBlank()) {
            logger.debug("Displaying HTML content for message");
            webEngine.loadContent(html);
            plainTextArea.setVisible(false);
            webViewContainer.setVisible(true);
        } else if (body != null && !body.isBlank()) {
            logger.debug("Displaying plain text content for message");
            // show as preformatted text in webview to preserve newlines
            String escaped = escapeHtml(body).replace("\n", "<br/>");
            webEngine.loadContent("<html><body><pre style='white-space:pre-wrap; font-family: sans-serif;'>" + escaped + "</pre></body></html>");
            plainTextArea.setVisible(false);
            webViewContainer.setVisible(true);
        } else {
            logger.debug("No content found for message");
            webEngine.loadContent("<html><body><i>(empty message)</i></body></html>");
            webViewContainer.setVisible(true);
        }

        // attachments
        List<AttachmentMetaDTO> atts = null;
        try {
            Object r = dto.getClass().getMethod("attachments").invoke(dto);
            //noinspection unchecked
            atts = (List<AttachmentMetaDTO>) r;
        } catch (Throwable t) {
            logger.trace("Failed to get attachments from DTO", t);
        }
        
        attachmentsList.getItems().clear();
        if (atts != null && !atts.isEmpty()) {
            logger.debug("Adding {} attachments to list", atts.size());
            attachmentsList.getItems().addAll(atts);
        } else {
            logger.debug("No attachments found for message");
        }
        
        logger.info("Message displayed successfully");
    }

    private void openSelectedAttachment() {
        AttachmentMetaDTO sel = attachmentsList.getSelectionModel().getSelectedItem();
        if (sel == null) {
            logger.warn("No attachment selected to open");
            return;
        }
        
        logger.info("Opening attachment: {}", sel.fileName());
        downloadAndOpenAttachment(sel);
    }

    private void saveSelectedAttachment() {
        AttachmentMetaDTO sel = attachmentsList.getSelectionModel().getSelectedItem();
        if (sel == null) {
            logger.warn("No attachment selected to save");
            return;
        }

        logger.info("Saving attachment: {}", sel.fileName());
        
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setInitialFileName(sel.fileName());
        File target = chooser.showSaveDialog(subjectLabel.getScene().getWindow());
        
        if (target == null) {
            logger.debug("Save dialog cancelled by user");
            return;
        }

        logger.debug("Saving attachment to: {}", target.getAbsolutePath());
        
        CompletableFuture.runAsync(() -> {
            try (InputStream in = loadAttachmentStream(sel)) {
                if (in == null) {
                    logger.error("Attachment stream is null for attachment: {}", sel.fileName());
                    throw new IllegalStateException("Attachment stream is null");
                }
                
                try (FileOutputStream fos = new FileOutputStream(target)) {
                    in.transferTo(fos);
                }
                
                logger.info("Attachment saved successfully to: {}", target.getAbsolutePath());
                eventBus.publish(new NotificationEvent(NotificationLevel.SUCCESS, "Saved attachment to " + target.getAbsolutePath(), null));
            } catch (Exception e) {
                logger.error("Failed to save attachment: {}", sel.fileName(), e);
                eventBus.publish(new NotificationEvent(NotificationLevel.ERROR, "Failed to save attachment: " + e.getMessage(), e));
            }
        });
    }

    private void downloadAndOpenAttachment(AttachmentMetaDTO meta) {
        logger.debug("Downloading and opening attachment: {}", meta.fileName());
        
        CompletableFuture.supplyAsync(() -> {
            try (InputStream in = loadAttachmentStream(meta)) {
                if (in == null) {
                    logger.error("Attachment stream is null for attachment: {}", meta.fileName());
                    throw new IllegalStateException("Attachment stream is null");
                }
                
                // write to temp file
                File tmp = Files.createTempFile("mailatt-", "-" + meta.fileName()).toFile();
                logger.debug("Created temp file: {}", tmp.getAbsolutePath());
                
                try (FileOutputStream fos = new FileOutputStream(tmp)) {
                    in.transferTo(fos);
                }
                
                tmp.deleteOnExit();
                return tmp;
            } catch (Exception e) {
                logger.error("Failed to download attachment: {}", meta.fileName(), e);
                throw new RuntimeException(e);
            }
        }).thenAccept(file -> {
            try {
                logger.debug("Attempting to open temp file: {}", file.getAbsolutePath());
                if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().open(file);
                    logger.info("Attachment opened successfully: {}", meta.fileName());
                    eventBus.publish(new NotificationEvent(NotificationLevel.SUCCESS, "Opened attachment " + file.getName(), null));
                } else {
                    logger.warn("Desktop is not supported, cannot open attachment");
                    eventBus.publish(new NotificationEvent(NotificationLevel.WARNING, "Cannot open attachment - desktop not supported", null));
                }
            } catch (Exception e) {
                logger.error("Failed to open attachment: {}", meta.fileName(), e);
                eventBus.publish(new NotificationEvent(NotificationLevel.ERROR, "Failed to open attachment: " + e.getMessage(), e));
            }
        }).exceptionally(ex -> {
            Throwable c = ex instanceof java.util.concurrent.CompletionException ? ex.getCause() : ex;
            logger.error("Exception during attachment opening: {}", meta.fileName(), c);
            eventBus.publish(new NotificationEvent(NotificationLevel.ERROR, "Failed to download/open attachment: " + c.getMessage(), c));
            return null;
        });
    }

    /**
     * Loads attachment InputStream via AttachmentService (caller must close). This method takes care of master password retrieval.
     */
    private InputStream loadAttachmentStream(AttachmentMetaDTO meta) throws Exception {
        logger.debug("Loading attachment stream for attachment ID: {}, filename: {}", meta.id(), meta.fileName());
        
        Optional<char[]> maybe = masterPasswordService.getCurrentMasterPassword();
        char[] mp = maybe.orElse(null);
        
        if (mp == null) {
            logger.warn("No master password available for loading attachment");
        } else {
            logger.debug("Master password retrieved successfully");
        }
        
        // AttachmentService contract: loadAttachment(Long attachmentId, char[] masterPassword)
        InputStream in = attachmentService.loadAttachment(meta.id(), mp);
        
        if (in == null) {
            logger.error("AttachmentService returned null stream for attachment ID: {}", meta.id());
        } else {
            logger.debug("Attachment stream obtained successfully for attachment ID: {}", meta.id());
        }
        
        // Do NOT clear mp here — it belongs to MasterPasswordService storage. If masterPasswordService returns a copy, it should be cleared by that service.
        return in;
    }

    // small helpers
    private static String safeStr(Object o) { 
        return o == null ? "" : o.toString(); 
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}