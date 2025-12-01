package ru.study.persistence.mapper;

import ru.study.core.dto.AttachmentMetaDTO;
import ru.study.core.dto.MessageDetailDTO;
import ru.study.core.dto.MessageSummaryDTO;
import ru.study.core.model.AttachmentReference;
import ru.study.core.model.EmailAddress;
import ru.study.core.model.Message;
import ru.study.persistence.entity.MessageEntity;
import ru.study.persistence.util.MapperUtils;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public final class MessageMapper {
    private MessageMapper() {}

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
        
        EmailAddress from = e.getSender() != null ? new EmailAddress(e.getSender()) : null;

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
            List<EmailAddress> to = rec == null ? Collections.emptyList() : MapperUtils.csvToEmails(rec);
            List<EmailAddress> ccList = cc == null ? Collections.emptyList() : MapperUtils.csvToEmails(cc);
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
}