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
 * Improved message viewer (fixed load/duplication issues).
 */
public class MessageViewController {

    @FXML public WebView webView;

    private final EventBus eventBus;
    private final AttachmentService attachmentService;

    // last successfully loaded *raw* HTML (inner / original string passed to showMessage)
    private volatile String lastLoadedHtml = "";

    // last requested raw HTML (set right before loadContent) — used by state listener
    private volatile String lastRequestedRawHtml = null;

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
        System.out.println("MessageViewController.initialize() instance=" + System.identityHashCode(this) + " webView=" + (webView==null ? "NULL" : webView));

        WebEngine engine = webView.getEngine();
        engine.setJavaScriptEnabled(true);

        var lw = engine.getLoadWorker();
        lw.stateProperty().addListener((obs, oldS, newS) -> {
            System.out.println("[WEBENGINE] state: " + newS + " (messageView instance=" + System.identityHashCode(this) + ")");
            // On success, remember the raw HTML we requested
            if (newS == javafx.concurrent.Worker.State.SUCCEEDED) {
                // commit lastLoadedHtml only after success
                lastLoadedHtml = lastRequestedRawHtml == null ? "" : lastRequestedRawHtml;
                System.out.println("[WEBENGINE] load SUCCEEDED — committed lastLoadedHtml (len=" + (lastLoadedHtml==null?0:lastLoadedHtml.length()) + ")");
            }

            // if a previous load finished (SUCCEEDED/CANCELLED/FAILED) and some other logic expects pending loads,
            // that logic will schedule new loads through showMessage's pending mechanism.
        });

        lw.exceptionProperty().addListener((obs, oldEx, newEx) -> {
            if (newEx != null) {
                System.err.println("[WEBENGINE] exception: " + newEx.getMessage());
                newEx.printStackTrace();
            }
        });

        // стартовая страница
        engine.loadContent("<html><body><h2>Message Preview</h2><p>Select a message to view content.</p></body></html>", "text/html; charset=UTF-8");
    }

    /** Backwards-compatible simple API used in MainWindowController */
    public void showHtml(String html) {
        System.out.println("[DEBUG] showHtml() called on instance=" + System.identityHashCode(this)
                + " thread=" + Thread.currentThread().getName()
                + " htmlLen=" + (html==null?"null":html.length()));
        showMessage(html, null);
    }

    /**
     * Показывает сообщение: html может быть null, attachments — список вложений (если есть).
     */
    public void showMessage(String html, List<AttachmentReference> attachments) {
        final String htmlLocal = html == null ? "" : html;
        final List<AttachmentReference> attachmentsLocal = attachments;

        // Быстрая дедупликация — если содержимое такое же, не трогаем WebView
        if (htmlLocal.equals(lastLoadedHtml)) {
            System.out.println("[DEBUG] showMessage: same as lastLoadedHtml -> skipping load");
            return;
        }

        // Если сейчас WebEngine занят загрузкой — поставим в pending и выйдем
        var lw = webView.getEngine().getLoadWorker();
        if (lw.getState() == javafx.concurrent.Worker.State.RUNNING) {
            synchronized (this) {
                // поместим в поле lastRequestedRawHtml как "pending" — но не затираем lastLoadedHtml
                lastRequestedRawHtml = htmlLocal;
            }
            System.out.println("[DEBUG] showMessage: load in progress, queued pendingHtml (len=" + htmlLocal.length() + ")");
            return;
        }

        // Иначе грузим прямо сейчас (на FX-потоке)
        Platform.runLater(() -> loadNowWithAttachments(htmlLocal, attachmentsLocal));
    }

    // ---- вспомогательные методы ----
    private void loadNowWithAttachments(String htmlLocal, List<AttachmentReference> attachmentsLocal) {
        try {
            System.out.println("[DEBUG] loadNowWithAttachments on instance=" + System.identityHashCode(this) + " htmlLen=" + htmlLocal.length());
            String processed;
            if (htmlLocal.isBlank()) {
                processed = wrapHtml(escapeHtml("No HTML content"), null);
            } else {
                processed = htmlLocal;
                if (attachmentsLocal != null && !attachmentsLocal.isEmpty() && attachmentService != null) {
                    try {
                        processed = inlineCidImages(processed, attachmentsLocal);
                    } catch (Exception e) {
                        if (eventBus != null) eventBus.publish(new NotificationEvent(NotificationLevel.INFO, "Failed inline attachments: " + e.getMessage(), e));
                    }
                }
                processed = wrapHtml(processed, null);
            }

            System.out.println("[DEBUG] showMessage -> loading content, length=" + (processed == null ? 0 : processed.length()));
            // передаём и "raw" и "wrapped"
            loadNow(processed, htmlLocal);
        } catch (Exception ex) {
            if (eventBus != null) eventBus.publish(new NotificationEvent(NotificationLevel.ERROR, "Failed to render message: " + ex.getMessage(), ex));
            webView.getEngine().loadContent("<html><body><p>(render error)</p></body></html>", "text/html; charset=UTF-8");
            lastLoadedHtml = "";
        }
    }

    /** Непосредственная загрузка в WebEngine; lastLoadedHtml НЕ обновляется сразу —
     *  оно будет обновлено в stateProperty listener при SUCCEEDED. */
    private void loadNow(String wrappedHtmlContent, String rawHtml) {
        if (webView == null || webView.getEngine() == null) {
            System.out.println("[DEBUG] loadNow: webView or engine is null!");
            return;
        }

        // установим, что мы запросили (raw) — этот текст будет закреплён как lastLoadedHtml только при SUCCEEDED
        lastRequestedRawHtml = rawHtml == null ? "" : rawHtml;

        webView.getEngine().loadContent(wrappedHtmlContent, "text/html; charset=UTF-8");
        // НЕ присваиваем lastLoadedHtml здесь!
    }

    private String wrapHtml(String innerHtml, String title) {
        if (innerHtml == null) innerHtml = "";
        String trimmed = innerHtml.trim().toLowerCase();
        // If it's already a full document, don't wrap again
        if (trimmed.startsWith("<!doctype") || trimmed.startsWith("<html")) {
            return innerHtml;
        }

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
                            if (a.getFileName() != null && a.getFileName().equalsIgnoreCase(normalized)) return true;
                            if (a.getId() != null && normalized.equals(a.getId().toString())) return true;
                            if (a.getFilePath() != null && a.getFilePath().toLowerCase().contains(normalized.toLowerCase())) return true;
                            return false;
                        }).findFirst().orElse(null);

                if (found != null && found.getId() != null) {
                    try (InputStream in = attachmentService.loadAttachment(found.getId(), null)) {
                        byte[] bytes = readAllBytes(in);
                        String b64 = Base64.getEncoder().encodeToString(bytes);
                        String ct = found.getContentType();
                        if (ct == null || ct.isBlank()) ct = "application/octet-stream";
                        replacementSrc = "data:" + ct + ";base64," + b64;
                    }
                } else if (found != null && found.getFilePath() != null) {
                    try (InputStream in = new java.io.FileInputStream(found.getFilePath())) {
                        byte[] bytes = readAllBytes(in);
                        String b64 = Base64.getEncoder().encodeToString(bytes);
                        String ct = found.getContentType();
                        if (ct == null || ct.isBlank()) ct = "application/octet-stream";
                        replacementSrc = "data:" + ct + ";base64," + b64;
                    } catch (Exception e) {
                        if (eventBus != null) eventBus.publish(new NotificationEvent(NotificationLevel.INFO, "Can't read attachment file for cid " + cid + ": " + e.getMessage(), e));
                    }
                }
            } catch (Exception e) {
                if (eventBus != null) eventBus.publish(new NotificationEvent(NotificationLevel.INFO, "Failed to inline cid " + cid + ": " + e.getMessage(), e));
            }

            String repl = "src=" + quote + replacementSrc + quote;
            m.appendReplacement(sb, Matcher.quoteReplacement(repl));
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

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
