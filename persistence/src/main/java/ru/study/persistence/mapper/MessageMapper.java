package ru.study.persistence.mapper;

import ru.study.core.dto.AttachmentMetaDTO;
import ru.study.core.dto.MessageDetailDTO;
import ru.study.core.dto.MessageSummaryDTO;
import ru.study.core.exception.ValidationException;
import ru.study.core.model.AttachmentReference;
import ru.study.core.model.EmailAddress;
import ru.study.core.model.Message;
import ru.study.persistence.entity.MessageEntity;
import ru.study.persistence.util.MapperUtils;

import jakarta.mail.internet.MimeUtility;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class MessageMapper {
    private MessageMapper() {}

    // pattern to match "Display Name <local@domain>"
    private static final Pattern ADDR_WITH_NAME = Pattern.compile("^(.*)<([^>]+)>$");

    public static Message toDomain(MessageEntity e) {
        if (e == null) return null;

        List<AttachmentReference> attachments = (e.getAttachments() == null)
            ? Collections.emptyList()
            : e.getAttachments().stream()
                .map(AttachmentMapper::toDomain)
                .collect(Collectors.toList());

        MapperUtils.RecipientLists lists = parseRecipientsFromEntity(e);
        List<EmailAddress> toList = lists.to;
        List<EmailAddress> ccList = lists.cc;

        EmailAddress from = null;
        if (e.getSender() != null && !e.getSender().isBlank()) {
            String senderEmail = null;
            try {
                senderEmail = extractEmailOnly(e.getSender());
                from = new EmailAddress(senderEmail);
            } catch (ValidationException ex) {
                // If sender is invalid — leave null (or you may fallback to raw string without validation)
                from = null;
            }
        }

        return new Message(
            e.getId(),
            e.getAccountId(),
            e.getFolder() != null ? e.getFolder().getServerName() : null,
            from,
            toList,
            ccList,
            e.getSubject(),
            extractSnippet(e),
            MapperUtils.toInstant(e.getSentDate()),
            Boolean.TRUE.equals(e.getIsSeen()),
            Boolean.TRUE.equals(e.getIsEncrypted()),
            attachments
        );
    }

    private static MapperUtils.RecipientLists parseRecipientsFromEntity(MessageEntity e) {
        String rec = e.getRecipients();
        String cc = e.getCc();
        if ((cc != null && !cc.isBlank()) || (rec != null && !rec.isBlank())) {
            List<EmailAddress> to = rec == null ? Collections.emptyList() : csvToEmailsSafe(rec);
            List<EmailAddress> ccList = cc == null ? Collections.emptyList() : csvToEmailsSafe(cc);
            return new MapperUtils.RecipientLists(to, ccList);
        } else {
            return new MapperUtils.RecipientLists(Collections.emptyList(), Collections.emptyList());
        }
    }

    private static String extractSnippet(MessageEntity e) {
        if (e.getSubject() != null && !e.getSubject().isBlank()) {
            return e.getSubject().length() > 120
                ? e.getSubject().substring(0, 120) + "..."
                : e.getSubject();
        }
        return "";
    }

    public static MessageSummaryDTO toSummaryDto(Message domain) {
        if (domain == null) return null;

        return new MessageSummaryDTO(
            domain.getId(),
            domain.getFrom() != null ? domain.getFrom().value() : "Unknown",
            domain.getSubject() != null ? domain.getSubject() : "",
            domain.getSnippet() != null ? domain.getSnippet() : "",
            domain.getDate(),
            domain.isSeen(),
            domain.isEncrypted(),
            !domain.getAttachments().isEmpty()
        );
    }

    public static MessageDetailDTO toDetailDto(Message domain, String bodyHtml, String bodyText, Boolean signatureValid) {
        if (domain == null) return null;

        List<AttachmentMetaDTO> attachments = domain.getAttachments().stream()
            .map(AttachmentMapper::domainToDto)
            .collect(Collectors.toList());

        List<String> to = domain.getTo().stream()
            .map(EmailAddress::value)
            .collect(Collectors.toList());

        List<String> cc = domain.getCc().stream()
            .map(EmailAddress::value)
            .collect(Collectors.toList());

        return new MessageDetailDTO(
            domain.getId(),
            domain.getAccountId(),
            domain.getFolderName(),
            domain.getFrom() != null ? domain.getFrom().value() : "Unknown",
            to,
            cc,
            domain.getSubject(),
            bodyHtml,
            bodyText,
            domain.getDate(),
            domain.isSeen(),
            domain.isEncrypted(),
            attachments,
            signatureValid
        );
    }

    // ======= helpers ========

    /**
     * Try to decode RFC2047 encoded display-name and extract only the email address.
     * Throws ValidationException if no email token with '@' can be found.
     */
    private static String extractEmailOnly(String raw) throws ValidationException {
        if (raw == null) throw new ValidationException("Empty address");
        String decoded = decodeDisplayName(raw).trim();

        // try "Name <addr@host>"
        Matcher m = ADDR_WITH_NAME.matcher(decoded);
        if (m.matches()) {
            String email = m.group(2).trim();
            email = cleanAddressToken(email);
            if (looksLikeEmail(email)) return email;
            // else fallthrough to other strategies
        }

        // try to find token containing '@' among split tokens
        String[] parts = decoded.split("[,;\\s]+");
        for (String p : parts) {
            String candidate = cleanAddressToken(p);
            if (looksLikeEmail(candidate)) return candidate;
        }

        // remove RFC2047 encoded words and try again (fallback)
        String stripped = decoded.replaceAll("=\\?.*?\\?=", "").replaceAll("[\"<>]", "").trim();
        if (stripped.contains("@")) {
            // take first token with @
            String[] toks = stripped.split("[,;\\s]+");
            for (String t : toks) {
                if (t.contains("@")) {
                    String candidate = cleanAddressToken(t);
                    if (looksLikeEmail(candidate)) return candidate;
                }
            }
        }

        throw new ValidationException("Invalid email address: " + raw);
    }

    private static String decodeDisplayName(String raw) {
        if (raw == null) return "";
        try {
            // try decode RFC2047 encoded words like =?utf-8?B?...?=
            return MimeUtility.decodeText(raw);
        } catch (UnsupportedEncodingException | NoClassDefFoundError e) {
            // If MimeUtility not available or decode fails - return original raw
            return raw;
        } catch (Throwable t) {
            return raw;
        }
    }

    private static String cleanAddressToken(String token) {
        if (token == null) return "";
        return token.replaceAll("[\"<>]", "").trim();
    }

    private static boolean looksLikeEmail(String s) {
        if (s == null) return false;
        s = s.trim();
        return s.contains("@") && !s.startsWith("@") && !s.endsWith("@");
    }

    private static List<EmailAddress> csvToEmailsSafe(String csv) {
        if (csv == null || csv.isBlank()) return Collections.emptyList();
        List<EmailAddress> out = new ArrayList<>();
        // split by comma or semicolon (keep simple)
        String[] parts = csv.split("\\s*,\\s*|\\s*;\\s*");
        for (String p : parts) {
            if (p == null || p.isBlank()) continue;
            try {
                String email = extractEmailOnly(p);
                out.add(new EmailAddress(email));
            } catch (ValidationException ve) {
                // skip invalid recipient but don't fail entire mapping
                // optionally log: System.out.println("Skipping invalid address: " + p + " -> " + ve.getMessage());
            }
        }
        return out;
    }
}
