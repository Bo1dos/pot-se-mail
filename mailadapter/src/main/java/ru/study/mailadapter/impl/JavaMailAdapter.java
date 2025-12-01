package ru.study.mailadapter.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.study.mailadapter.api.MailAdapter;
import ru.study.mailadapter.exception.*;
import ru.study.mailadapter.model.*;

import jakarta.activation.DataHandler;
import jakarta.activation.DataSource;
import jakarta.activation.FileDataSource;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import jakarta.mail.search.FlagTerm;
import jakarta.mail.util.ByteArrayDataSource;
import java.io.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * JavaMail-based implementation of MailAdapter.
 * Thread-safe for single connection instance (synchronized on this).
 */
public class JavaMailAdapter implements MailAdapter {

    private static final Logger log = LoggerFactory.getLogger(JavaMailAdapter.class);

    private Session session;
    private Store store;
    private volatile boolean connected = false;
    private AccountConfig currentConfig;

    @Override
    public synchronized void connect(AccountConfig config) throws MailConnectException {
        try {
            this.currentConfig = config;
            Properties props = new Properties();
            // IMAP
            props.put("mail.store.protocol", "imap");
            props.put("mail.imap.host", config.getImapHost());
            props.put("mail.imap.port", String.valueOf(config.getImapPort()));
            props.put("mail.imap.ssl.enable", String.valueOf(config.isImapSsl()));
            // SMTP
            props.put("mail.smtp.host", config.getSmtpHost());
            props.put("mail.smtp.port", String.valueOf(config.getSmtpPort()));
            props.put("mail.smtp.auth", "true");
            props.put("mail.smtp.ssl.enable", String.valueOf(config.isSmtpSsl()));
            session = Session.getInstance(props);
            store = session.getStore("imap");
            store.connect(config.getImapHost(), config.getUsername(), config.getPassword());
            connected = true;
            log.info("Connected to mail store: {}:{}", config.getImapHost(), config.getImapPort());
        } catch (MessagingException e) {
            log.error("Failed to connect to mail store: {}:{}", config.getImapHost(), config.getImapPort(), e);
            throw new MailConnectException("Failed to connect to mail store", e);
        }
    }

    @Override
    public synchronized void disconnect() {
        try {
            if (store != null && store.isConnected()) {
                store.close();
                log.debug("Disconnected from mail store");
            }
        } catch (MessagingException ignored) {}
        connected = false;
        currentConfig = null;
    }

    private void ensureConnected() throws MailConnectException {
        if (!connected || store == null || !store.isConnected()) {
            throw new MailConnectException("Not connected to mail store", null);
        }
    }

    @Override
    public synchronized List<MailFolder> listFolders() throws MailException {
        ensureConnected();
        try {
            Folder[] folders = store.getDefaultFolder().list();
            List<MailFolder> list = new ArrayList<>();
            for (Folder f : folders) {
                int count = 0;
                try {
                    count = f.getMessageCount();
                } catch (Exception ignored){}
                list.add(new MailFolder(f.getFullName(), count));
            }
            log.debug("Listed {} folders", list.size());
            return list;
        } catch (MessagingException e) {
            log.error("Failed to list folders", e);
            throw new MailFetchException("Failed to list folders", e);
        }
    }

    @Override
    public synchronized List<MailHeader> fetchHeaders(String folderName, long sinceUid, int limit) throws MailException {
        ensureConnected();
        Folder f = null;
        try {
            f = store.getFolder(folderName);
            if (!f.exists()) throw new MailFetchException("Folder not found: " + folderName, null);
            f.open(Folder.READ_ONLY);
            UIDFolder uidf = (UIDFolder) f;
            Message[] msgs;
            if (sinceUid <= 0) {
                msgs = f.getMessages();
            } else {
                msgs = uidf.getMessagesByUID(sinceUid + 1, UIDFolder.LASTUID);
            }

            // if limit requested, sort by date descending and take limit
            List<Message> list = Arrays.asList(msgs);
            list.sort((m1, m2) -> {
                try {
                    Date d1 = m1.getSentDate();
                    Date d2 = m2.getSentDate();
                    if (d1 == null && d2 == null) return 0;
                    if (d1 == null) return 1;
                    if (d2 == null) return -1;
                    return d2.compareTo(d1);
                } catch (MessagingException e) { return 0; }
            });

            if (limit > 0 && list.size() > limit) list = list.subList(0, limit);

            List<MailHeader> headers = new ArrayList<>();
            for (Message m : list) {
                long uid = uidf.getUID(m);
                String[] froms = extractAddresses(m.getFrom());
                String from = froms.length > 0 ? froms[0] : "";
                String subject = safe(m::getSubject);
                Date sent = safeDate(m::getSentDate);
                boolean seen = safeFlag(m::isSet, Flags.Flag.SEEN);
                boolean hasAttachments = messageHasAttachments(m);
                String messageId = safe(() -> {
                    String[] mids = m.getHeader("Message-ID");
                    return (mids == null || mids.length == 0) ? null : mids[0];
                });
                headers.add(new MailHeader(uid, messageId, from, subject, sent == null ? Instant.now() : sent.toInstant(), seen, hasAttachments));
            }
            log.debug("Fetched {} headers from folder '{}' since UID {}", headers.size(), folderName, sinceUid);
            return headers;
        } catch (MessagingException e) {
            log.error("Failed to fetch headers from folder '{}' since UID {}", folderName, sinceUid, e);
            throw new MailFetchException("Failed to fetch headers: " + e.getMessage(), e);
        } finally {
            try { if (f != null && f.isOpen()) f.close(false); } catch (MessagingException ignored) {}
        }
    }

    @Override
    public synchronized RawMail fetchMessage(String folderName, long uid) throws MailException {
        ensureConnected();
        Folder f = null;
        try {
            f = store.getFolder(folderName);
            if (!f.exists()) throw new MailFetchException("Folder not found: " + folderName, null);
            f.open(Folder.READ_ONLY);
            UIDFolder uidf = (UIDFolder) f;
            Message msg = uidf.getMessageByUID(uid);
            if (msg == null) throw new MailFetchException("Message with UID " + uid + " not found", null);

            Map<String, List<String>> headers = extractHeaders(msg);
            String[] bodies = extractBodies(msg); // [plain, html]
            List<AttachmentDescriptor> attachments = extractAttachments(msg);

            log.debug("Fetched message UID {} from folder '{}' with {} attachments", uid, folderName, attachments.size());
            return new RawMail(uid, headers, bodies[0], bodies[1], attachments);
        } catch (MessagingException | IOException e) {
            log.error("Failed to fetch message UID {} from folder '{}'", uid, folderName, e);
            throw new MailFetchException("Failed to fetch message: " + e.getMessage(), e);
        } finally {
            try { if (f != null && f.isOpen()) f.close(false); } catch (MessagingException ignored) {}
        }
    }

    @Override
    public synchronized InputStream openAttachmentStream(String folderName, long uid, String attachmentId) throws MailException {
        ensureConnected();
        Folder f = null;
        try {
            f = store.getFolder(folderName);
            if (!f.exists()) throw new MailFetchException("Folder not found: " + folderName, null);
            f.open(Folder.READ_ONLY);
            UIDFolder uidf = (UIDFolder) f;
            Message msg = uidf.getMessageByUID(uid);
            if (msg == null) throw new MailFetchException("Message not found: uid=" + uid, null);

            Part part = findAttachmentPart(msg, attachmentId);
            if (part == null) throw new MailFetchException("Attachment not found: " + attachmentId, null);
            log.debug("Opened attachment stream for UID {} attachment '{}'", uid, attachmentId);
            return part.getInputStream(); // caller must close
        } catch (MessagingException | IOException e) {
            log.error("Failed to open attachment stream for UID {} attachment '{}'", uid, attachmentId, e);
            throw new MailFetchException("Failed to open attachment stream", e);
        } finally {
            // keep folder open? we close after stream usage by caller; but here we close to be safe:
            try { if (f != null && f.isOpen()) f.close(false); } catch (MessagingException ignored) {}
        }
    }

    @Override
    public synchronized void send(RawOutgoingMail mail) throws MailException {
        log.info("Sending mail from '{}' with subject '{}' and {} attachments", 
                mail.getFrom(), mail.getSubject(), 
                mail.getAttachments() == null ? 0 : mail.getAttachments().size());
                
        // builds MimeMessage and sends via SMTP
        try {
            if (session == null) {
                // create a new session for SMTP using currentConfig if available
                if (currentConfig == null) throw new MailSendException("No SMTP configuration available", null);
                Properties props = new Properties();
                props.put("mail.smtp.host", currentConfig.getSmtpHost());
                props.put("mail.smtp.port", String.valueOf(currentConfig.getSmtpPort()));
                props.put("mail.smtp.auth", "true");
                props.put("mail.smtp.ssl.enable", String.valueOf(currentConfig.isSmtpSsl()));
                session = Session.getInstance(props);
            }

            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(mail.getFrom()));
            addAddresses(message, Message.RecipientType.TO, mail.getTo());
            addAddresses(message, Message.RecipientType.CC, mail.getCc());
            addAddresses(message, Message.RecipientType.BCC, mail.getBcc());
            message.setSubject(mail.getSubject());

            if (mail.getAttachments() == null || mail.getAttachments().isEmpty()) {
                if (mail.isHtml()) {
                    message.setContent(mail.getBody(), "text/html; charset=utf-8");
                } else {
                    message.setText(mail.getBody(), "utf-8");
                }
            } else {
                // multipart with body part + attachments
                MimeBodyPart bodyPart = new MimeBodyPart();
                if (mail.isHtml()) bodyPart.setContent(mail.getBody(), "text/html; charset=utf-8");
                else bodyPart.setText(mail.getBody(), "utf-8");

                Multipart mp = new MimeMultipart();
                mp.addBodyPart(bodyPart);

                for (OutgoingAttachment att : mail.getAttachments()) {
                    MimeBodyPart attPart = new MimeBodyPart();
                    DataSource ds;
                    InputStream in = att.getInput();
                    if (att.getFile() != null) {
                        // file-backed — use streaming FileDataSource
                        try {
                            ds = new FileDataSource(att.getFile());
                            log.debug("Using FileDataSource for file-backed attachment: {}", att.getFilename());
                        } catch (Exception ex) {
                            log.warn("FileDataSource failed for {}, falling back to InputStream", att.getFilename(), ex);
                            // fallback to InputStreamDataSource if file can't be used
                            if (in == null) in = new ByteArrayInputStream(new byte[0]);
                            ds = new InputStreamDataSource(in, att.getContentType() == null ? "application/octet-stream" : att.getContentType(), att.getFilename());
                        }
                    } else if (in == null) {
                        ds = new ByteArrayDataSource(new byte[0], att.getContentType() == null ? "application/octet-stream" : att.getContentType());
                    } else {
                        // streaming DataSource that hands InputStream to JavaMail (does not read whole file here)
                        ds = new InputStreamDataSource(in, att.getContentType() == null ? "application/octet-stream" : att.getContentType(), att.getFilename());
                    }
                    attPart.setDataHandler(new DataHandler(ds));
                    attPart.setFileName(att.getFilename());
                    attPart.setDisposition(Part.ATTACHMENT);
                    mp.addBodyPart(attPart);
                }

                message.setContent(mp);
            }

            // send via Transport
            Transport transport = session.getTransport("smtp");
            try {
                transport.connect(currentConfig.getSmtpHost(), currentConfig.getUsername(), currentConfig.getPassword());
                transport.sendMessage(message, message.getAllRecipients());
                log.info("Mail sent successfully from '{}' with subject '{}'", mail.getFrom(), mail.getSubject());
            } finally {
                try { transport.close(); } catch (MessagingException ignored) {}
            }
        } catch (MessagingException e) {
            log.error("Failed to send mail from '{}' with subject '{}'", mail.getFrom(), mail.getSubject(), e);
            throw new MailSendException("Failed to send message", e);
        }
    }

    // ---------------- helpers ----------------

    private static void addAddresses(MimeMessage msg, Message.RecipientType type, List<String> addrs) throws MessagingException {
        if (addrs == null || addrs.isEmpty()) return;
        Address[] arr = new Address[addrs.size()];
        for (int i = 0; i < addrs.size(); i++) arr[i] = new InternetAddress(addrs.get(i));
        msg.setRecipients(type, arr);
    }

    private static byte[] toBytes(InputStream in) throws IOException {
        try (ByteArrayOutputStream bout = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) bout.write(buf, 0, r);
            return bout.toByteArray();
        } finally {
            try { in.close(); } catch (IOException ignored) {}
        }
    }

    private static String[] extractAddresses(Address[] addresses) {
        if (addresses == null || addresses.length == 0) return new String[0];
        String[] out = new String[addresses.length];
        for (int i = 0; i < addresses.length; i++) out[i] = addresses[i].toString();
        return out;
    }

    private static <T> T safe(CheckedSupplier<T> s) {
        try { return s.get(); } catch (Exception ex) { return null; }
    }

    private static Date safeDate(CheckedSupplier<Date> s) {
        try { return s.get(); } catch (Exception ex) { return null; }
    }

    private static boolean safeFlag(FlagChecker checker, Flags.Flag flag) {
        try { return checker.check(flag); } catch (Exception ex) { return false; }
    }

    private static boolean messageHasAttachments(Message m) {
        try {
            if (m.isMimeType("multipart/*")) {
                Multipart mp = (Multipart) m.getContent();
                for (int i = 0; i < mp.getCount(); i++) {
                    BodyPart bp = mp.getBodyPart(i);
                    String disp = bp.getDisposition();
                    if (disp != null && (disp.equalsIgnoreCase(Part.ATTACHMENT) || disp.equalsIgnoreCase(Part.INLINE))) return true;
                }
            }
            return false;
        } catch (Exception ex) { return false; }
    }

    private static List<AttachmentDescriptor> extractAttachments(Message m) throws MessagingException, IOException {
        List<AttachmentDescriptor> out = new ArrayList<>();
        if (!m.isMimeType("multipart/*")) return out;
        Multipart mp = (Multipart) m.getContent();
        int partIndex = 0;
        for (int i = 0; i < mp.getCount(); i++) {
            BodyPart bp = mp.getBodyPart(i);
            partIndex = i;
            String disp = bp.getDisposition();
            if (disp != null && (disp.equalsIgnoreCase(Part.ATTACHMENT) || disp.equalsIgnoreCase(Part.INLINE))) {
                String filename = bp.getFileName();
                String contentType = bp.getContentType();
                long size = -1;
                try {
                    InputStream is = bp.getInputStream();
                    // cannot compute size without reading — leave -1
                    is.close();
                } catch (Exception ignored) {}
                String id = "part-" + i;
                out.add(new AttachmentDescriptor(id, filename, contentType, size, partIndex));
            }
        }
        return out;
    }

    private static Part findAttachmentPart(Message m, String attachmentId) throws MessagingException, IOException {
        if (!m.isMimeType("multipart/*")) return null;
        Multipart mp = (Multipart) m.getContent();
        for (int i = 0; i < mp.getCount(); i++) {
            String id = "part-" + i;
            if (id.equals(attachmentId)) return mp.getBodyPart(i);
        }
        return null;
    }

    private static Map<String, List<String>> extractHeaders(Message m) throws MessagingException {
        Map<String, List<String>> map = new HashMap<>();
        @SuppressWarnings("unchecked")
        Enumeration<Header> headers = m.getAllHeaders();
        while (headers.hasMoreElements()) {
            Header h = headers.nextElement();
            map.computeIfAbsent(h.getName(), k -> new ArrayList<>()).add(h.getValue());
        }
        return map;
    }

    private static String[] extractBodies(Message m) throws MessagingException, IOException {
        String plain = null;
        String html = null;
        if (m.isMimeType("text/plain")) {
            plain = (String) m.getContent();
        } else if (m.isMimeType("text/html")) {
            html = (String) m.getContent();
        } else if (m.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) m.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                BodyPart bp = mp.getBodyPart(i);
                if (bp.isMimeType("text/plain") && plain == null) {
                    plain = (String) bp.getContent();
                } else if (bp.isMimeType("text/html") && html == null) {
                    html = (String) bp.getContent();
                } else if (bp.isMimeType("multipart/*")) {
                    // nested multiparts
                    // For simplicity we only handle first-level text parts
                }
            }
        }
        return new String[]{ plain, html };
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> { T get() throws Exception; }

    @FunctionalInterface
    private interface FlagChecker { boolean check(Flags.Flag flag) throws Exception; }


    private static final class InputStreamDataSource implements DataSource {
        private final InputStream in;
        private final String contentType;
        private final String name;

        InputStreamDataSource(InputStream in, String contentType, String name) {
            this.in = in;
            this.contentType = contentType == null ? "application/octet-stream" : contentType;
            this.name = name == null ? "attachment" : name;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return in; // caller (JavaMail) will read and close
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            throw new UnsupportedOperationException("Not supported");
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public String getName() {
            return name;
        }
    }
}