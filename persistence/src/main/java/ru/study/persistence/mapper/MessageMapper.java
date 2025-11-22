package ru.study.persistence.mapper;

import ru.study.core.dto.AttachmentMetaDTO;
import ru.study.core.dto.MessageDetailDTO;
import ru.study.core.dto.MessageSummaryDTO;
import ru.study.core.model.AttachmentReference;
import ru.study.core.model.EmailAddress;
import ru.study.core.model.Message;
import ru.study.persistence.entity.MessageEntity;
import ru.study.persistence.util.MapperUtils;

import java.util.List;
import java.util.stream.Collectors;

/**
 * MessageMapper — класс для маппинга между MessageEntity, domain Message и DTO.
 */
public final class MessageMapper {
    private MessageMapper() {}

    public static Message toDomain(MessageEntity e) {
        if (e == null) return null;

        List<AttachmentReference> attachments = (e.getAttachments() == null)
            ? List.of()
            : e.getAttachments().stream().map(AttachmentMapper::toDomain).collect(Collectors.toList());

        // Prefer explicit cc column if present; otherwise parse recipients (legacy or JSON)
        MapperUtils.RecipientLists lists;
        if (e.getCc() != null && !e.getCc().isBlank()) {
            List<EmailAddress> to = MapperUtils.csvToEmails(e.getRecipients());
            List<EmailAddress> cc = MapperUtils.csvToEmails(e.getCc());
            lists = new MapperUtils.RecipientLists(to, cc);
        } else {
            lists = MapperUtils.parseRecipients(e.getRecipients());
        }

        List<EmailAddress> toList = lists.to;
        List<EmailAddress> ccList = lists.cc;
        EmailAddress from = new EmailAddress(e.getSender());

        // Note: Message constructor expects (id, accountId, folderName, from, to, cc, subject, snippet, date, seen, encrypted, attachments)
        boolean seen = false; // MessageEntity doesn't have 'seen' field — service should set it if available
        boolean encrypted = Boolean.TRUE.equals(e.getIsEncrypted());

        return new Message(
            e.getId(),
            e.getAccountId(),
            e.getFolder() == null ? null : e.getFolder().getServerName(),
            from,
            toList,
            ccList,
            e.getSubject(),
            snippetFromEntity(e),
            MapperUtils.toInstant(e.getSentDate()),
            seen,
            encrypted,
            attachments
        );
    }

    private static String snippetFromEntity(MessageEntity e) {
        if (e.getSubject() != null && !e.getSubject().isBlank()) {
            return e.getSubject().length() > 120 ? e.getSubject().substring(0, 120) : e.getSubject();
        }
        // fallback: try generate snippet from decrypted body later in service
        return "";
    }

    public static MessageEntity toEntity(Message domain) {
        if (domain == null) return null;
        MessageEntity e = new MessageEntity();
        e.setId(domain.getId());
        e.setAccountId(domain.getAccountId());
        // Folder entity assignment should be handled in service layer (by loading FolderEntity)
        e.setFolder(null);
        e.setSubject(domain.getSubject());
        e.setSender(domain.getFrom().value());
        e.setRecipients(MapperUtils.emailsToCsv(domain.getTo()));
        // For cc in entity: convert domain.cc to CSV if domain contains cc (it does)
        // but domain.getCc() exists in your model, so:
        e.setCc(domain.getCc() == null ? null : MapperUtils.emailsToCsv(domain.getCc()));
        e.setSentDate(MapperUtils.toOffsetDateTime(domain.getDate()));
        e.setIsEncrypted(domain.isEncrypted());
        return e;
    }

    public static MessageSummaryDTO toSummaryDto(Message domain) {
        if (domain == null) return null;
        return new MessageSummaryDTO(
            domain.getId(),
            domain.getFrom().value(),
            domain.getSubject(),
            domain.getSnippet(),
            domain.getDate(),
            domain.isSeen(),
            domain.isEncrypted(),
            !domain.getAttachments().isEmpty()
        );
    }

    public static MessageDetailDTO toDetailDto(MessageEntity e) {
        if (e == null) return null;

        List<AttachmentMetaDTO> attachments = (e.getAttachments() == null) ? List.of()
            : e.getAttachments().stream().map(AttachmentMapper::toDto).collect(Collectors.toList());

        // build recipients lists (prefer explicit cc column)
        MapperUtils.RecipientLists lists;
        if (e.getCc() != null && !e.getCc().isBlank()) {
            List<EmailAddress> to = MapperUtils.csvToEmails(e.getRecipients());
            List<EmailAddress> cc = MapperUtils.csvToEmails(e.getCc());
            lists = new MapperUtils.RecipientLists(to, cc);
        } else {
            lists = MapperUtils.parseRecipients(e.getRecipients());
        }

        List<String> toList = lists.to.stream().map(EmailAddress::value).collect(Collectors.toList());
        List<String> ccList = lists.cc.stream().map(EmailAddress::value).collect(Collectors.toList());


        Boolean seen = Boolean.FALSE; // set false by default; service may override if flag exists
        Boolean encrypted = Boolean.TRUE.equals(e.getIsEncrypted());
        Boolean signatureValid = null; // crypto service can set later

        return new MessageDetailDTO(
            e.getId(),
            e.getAccountId(),
            e.getFolder() == null ? null : e.getFolder().getServerName(),
            e.getSender(),
            toList,
            ccList,
            e.getSubject(),
            null, // bodyHtml — service fills (decrypt/format)
            null, // bodyText — service fills
            MapperUtils.toInstant(e.getSentDate()),
            seen,
            encrypted,
            attachments,
            signatureValid
        );
    }
}
