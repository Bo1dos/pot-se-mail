package ru.study.ui.fx.controller;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import ru.study.core.event.NotificationEvent;
import ru.study.core.event.NotificationLevel;
import ru.study.core.event.bus.EventBus;
import ru.study.core.model.AttachmentReference;
import ru.study.service.api.AttachmentService;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Improved message viewer:
 * - provides showHtml(String) for backward compat
 * - provides showMessage(html, attachments) which inlines cid: images (best-effort)
 * - wraps HTML into full document with meta/style
 * - enables JS (optional)
 *
 * Notes:
 * - AttachmentReference in your model doesn't have contentId; we try to match cid -> fileName or id heuristics.
 * - AttachmentService.loadAttachment(id, masterPassword) is used when attachment.id != null.
 * - For encrypted attachments you must pass master password into loadAttachment; here we pass null (adjust if needed).
 */
public class MessageViewController {

    @FXML public WebView webView;

    private final EventBus eventBus; // optional, для нотификаций
    private final AttachmentService attachmentService; // may be null if not injected

    // pattern to find src="cid:..."" (also ' and without quotes variants handled too)
    private static final Pattern CID_PATTERN = Pattern.compile("src\\s*=\\s*([\"'])cid:([^\"']+)\\1", Pattern.CASE_INSENSITIVE);

    public MessageViewController() {
        this(null, null);
    }

    public MessageViewController(AttachmentService attachmentService, EventBus eventBus) {
        this.attachmentService = attachmentService;
        this.eventBus = eventBus;
    }

    @FXML
    public void initialize() {
        WebEngine engine = webView.getEngine();
        engine.setJavaScriptEnabled(true); // по желанию
        engine.loadContent("<html><body><h2>Message Preview</h2><p>Select a message to view content.</p></body></html>", "text/html");
    }

    /** Backwards-compatible simple API used in MainWindowController */
    public void showHtml(String html) {
        showMessage(html, null);
    }

    /**
     * Показывает сообщение: html может быть null, attachments — список вложений (если есть).
     * Не изменяет переменные из внешней области видимости (lambda-safe).
     */
    public void showMessage(String html, List<AttachmentReference> attachments) {
        final String htmlLocal = html;
        final List<AttachmentReference> attachmentsLocal = attachments;

        Platform.runLater(() -> {
            try {
                String toShow;
                if (htmlLocal == null || htmlLocal.isBlank()) {
                    toShow = wrapHtml(escapeHtml("No HTML content"), null);
                } else {
                    String processed = htmlLocal;
                    // inline cid: images if attachments and attachmentService available
                    if (attachmentsLocal != null && !attachmentsLocal.isEmpty() && attachmentService != null) {
                        processed = inlineCidImages(processed, attachmentsLocal);
                    }
                    // ensure full wrapper (meta charset + basic style)
                    toShow = wrapHtml(processed, null);
                }
                webView.getEngine().loadContent(toShow, "text/html; charset=UTF-8");
            } catch (Exception ex) {
                if (eventBus != null) eventBus.publish(new NotificationEvent(NotificationLevel.ERROR, "Failed to render message: " + ex.getMessage(), ex));
                webView.getEngine().loadContent("<html><body><p>(render error)</p></body></html>");
            }
        });
    }

    private String wrapHtml(String innerHtml, String title) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html><head><meta charset=\"utf-8\"/>");
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>");
        sb.append("<style>");
        sb.append("body{font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial; padding:10px; }");
        sb.append("img{max-width:100%;height:auto;}");
        sb.append("</style>");
        sb.append("</head><body>");
        if (title != null && !title.isBlank()) sb.append("<h1>").append(escapeHtml(title)).append("</h1>");
        sb.append(innerHtml == null ? "" : innerHtml);
        sb.append("</body></html>");
        return sb.toString();
    }

    /**
     * Попытка инлайнить cid: ссылки в data:URL'ы.
     * Поскольку AttachmentReference не содержит contentId, используем эвристики:
     * - сравниваем cid с fileName,
     * - сравниваем с id (строкой),
     * - если attachment.filePath содержит cid
     *
     * Если найден attachment с id != null — пытаемся загрузить через AttachmentService.loadAttachment(id, null).
     * Если ничего не найдено — оставляем ссылку как есть.
     */
    private String inlineCidImages(String html, List<AttachmentReference> attachments) {
        Matcher m = CID_PATTERN.matcher(html);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String quote = m.group(1);
            String cid = m.group(2);
            String replacementSrc = "cid:" + cid; // default keep original
            try {
                String normalized = cid.replaceAll("^<|>$", "");
                AttachmentReference found = attachments.stream()
                        .filter(a -> {
                            // try filename match (case-insensitive)
                            if (a.getFileName() != null && a.getFileName().equalsIgnoreCase(normalized)) return true;
                            // try id match
                            if (a.getId() != null && normalized.equals(a.getId().toString())) return true;
                            // try path contains
                            if (a.getFilePath() != null && a.getFilePath().toLowerCase().contains(normalized.toLowerCase())) return true;
                            // else false
                            return false;
                        }).findFirst().orElse(null);

                if (found != null && found.getId() != null) {
                    // try to load bytes from service
                    try (InputStream in = attachmentService.loadAttachment(found.getId(), null)) {
                        byte[] bytes = readAllBytes(in);
                        String b64 = Base64.getEncoder().encodeToString(bytes);
                        String ct = found.getContentType();
                        if (ct == null || ct.isBlank()) ct = "application/octet-stream";
                        replacementSrc = "data:" + ct + ";base64," + b64;
                    }
                } else {
                    // found but no id — maybe filePath exists and readable by JVM; try to load file directly (best-effort)
                    if (found != null && found.getFilePath() != null) {
                        try (InputStream in = new java.io.FileInputStream(found.getFilePath())) {
                            byte[] bytes = readAllBytes(in);
                            String b64 = Base64.getEncoder().encodeToString(bytes);
                            String ct = found.getContentType();
                            if (ct == null || ct.isBlank()) ct = "application/octet-stream";
                            replacementSrc = "data:" + ct + ";base64," + b64;
                        } catch (Exception e) {
                            // can't read file, fallback keep original
                            if (eventBus != null) eventBus.publish(new NotificationEvent(NotificationLevel.INFO, "Can't read attachment file for cid " + cid + ": " + e.getMessage(), e));
                        }
                    }
                }
            } catch (Exception e) {
                if (eventBus != null) eventBus.publish(new NotificationEvent(NotificationLevel.INFO, "Failed to inline cid " + cid + ": " + e.getMessage(), e));
            }

            // replace src="cid:..." with src="...replacement..."
            // m.group(1) = quote char
            String repl = "src=" + quote + replacementSrc + quote;
            // appendReplacement needs escaped replacement (but we give plain string)
            m.appendReplacement(sb, repl);
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static byte[] readAllBytes(InputStream in) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) != -1) baos.write(buf, 0, r);
        return baos.toByteArray();
    }

    // Очень простой HTML-эскейп — для fallback
    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
