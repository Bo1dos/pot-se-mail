package ru.study.persistence.mapper;

import ru.study.core.dto.AccountDTO;
import ru.study.core.model.Account;
import ru.study.core.model.EmailAddress;
import ru.study.persistence.entity.AccountEntity;
import ru.study.persistence.util.MapperUtils;

// TODO: рефактор, под core и persistence
public final class AccountMapper {
    private AccountMapper() {}

    public static Account toDomain(AccountEntity e) {
        if (e == null) return null;
        EmailAddress email = new EmailAddress(e.getEmail());
        return new Account(
            e.getId(),
            email,
            e.getDisplayName(),
            e.getImapServer(),
            e.getImapPort() == null ? 0 : e.getImapPort(),
            e.getSmtpServer(),
            e.getSmtpPort() == null ? 0 : e.getSmtpPort(),
            Boolean.TRUE.equals(e.getIsUseTls()),
            MapperUtils.toInstant(e.getCreatedAt())
        );
    }

    public static AccountEntity toEntity(Account domain) {
        if (domain == null) return null;
        AccountEntity e = new AccountEntity();
        e.setId(domain.getId());
        e.setEmail(domain.getEmail().value());
        e.setDisplayName(domain.getDisplayName());
        e.setImapServer(domain.getImapHost());
        e.setImapPort(domain.getImapPort());
        e.setSmtpServer(domain.getSmtpHost());
        e.setSmtpPort(domain.getSmtpPort());
        e.setIsUseTls(domain.isUseTls());
        e.setCreatedAt(MapperUtils.toOffsetDateTime(domain.getCreatedAt()));
        return e;
    }

    public static AccountDTO toDto(Account domain) {
        if (domain == null) return null;
        return new AccountDTO(
            domain.getId(),
            domain.getEmail().value(),
            domain.getDisplayName(),
            domain.getImapHost(),
            domain.getImapPort(),
            domain.getSmtpHost(),
            domain.getSmtpPort(),
            domain.isUseTls()
        );
    }

    public static Account fromDto(AccountDTO dto) {
        if (dto == null) return null;
        
        Integer imapPort = dto.imapPort();
        Integer smtpPort = dto.smtpPort();
        Boolean useTls = dto.useTls();

        return new Account(
            dto.id(),
            new EmailAddress(dto.email()),
            dto.displayName(),
            dto.imapServer(),
            imapPort == null ? 0 : imapPort,
            dto.smtpServer(),
            smtpPort == null ? 0 : smtpPort,
            Boolean.TRUE.equals(useTls),
            null
        );
    }
}