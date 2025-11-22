package ru.study.persistence.util;

import java.util.stream.Collectors;

import ru.study.core.dto.DraftDTO;
import ru.study.core.model.EmailAddress;
import ru.study.persistence.entity.MessageEntity;

public final class DraftMapper {
    private DraftMapper() {}

    public static DraftDTO toDto(MessageEntity e) {
        if (e == null) return null;
        var lists = MapperUtils.parseRecipients(e.getRecipients());
        return new DraftDTO(
            e.getId(),
            e.getAccountId(),
            e.getSubject(),
            lists.to.stream().map(EmailAddress::value).collect(Collectors.toList()),
            lists.cc.stream().map(EmailAddress::value).collect(Collectors.toList()),
            null, // bodyHtml - service fills
            MapperUtils.toInstant(e.getCreatedAt())
        );
    }

    public static MessageEntity fromDto(DraftDTO dto) {
        if (dto == null) return null;
        MessageEntity e = new MessageEntity();
        e.setId(dto.id());
        e.setAccountId(dto.accountId());
        e.setSubject(dto.subject());
        e.setRecipients(MapperUtils.emailsToCsv(dto.to().stream().map(EmailAddress::new).collect(Collectors.toList())));
        // folder assignment to DRAFTS must be done by service
        e.setSentDate(MapperUtils.toOffsetDateTime(dto.modifiedAt()));
        return e;
    }
}
