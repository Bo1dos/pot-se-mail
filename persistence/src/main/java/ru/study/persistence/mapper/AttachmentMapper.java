package ru.study.persistence.mapper;

import ru.study.core.dto.AttachmentMetaDTO;
import ru.study.core.model.AttachmentReference;
import ru.study.persistence.entity.AttachmentEntity;
import ru.study.persistence.entity.MessageEntity;

public final class AttachmentMapper {
    private AttachmentMapper() {}

    public static AttachmentReference toDomain(AttachmentEntity e) {
        if (e == null) return null;
        
        boolean storedInDb = e.getEncryptedBlob() != null && e.getEncryptedBlob().length > 0;
        
        return new AttachmentReference(
            e.getId(),
            e.getFilePath(),
            e.getFilename() != null ? e.getFilename() : "unknown",
            e.getSize() != null ? e.getSize() : 0L,
            storedInDb,
            e.getContentType()
        );
    }

    public static AttachmentEntity toEntity(AttachmentReference domain) {
        if (domain == null) return null;
        
        AttachmentEntity e = new AttachmentEntity();
        e.setId(domain.getId());
        e.setFilePath(domain.getFilePath());
        e.setFilename(domain.getFileName());
        e.setSize(domain.getSize());
        e.setContentType(domain.getContentType());
        
        return e;
    }

    public static AttachmentEntity toEntity(AttachmentReference domain, MessageEntity message) {
        if (domain == null) return null;
        AttachmentEntity e = new AttachmentEntity();
        e.setId(domain.getId());
        e.setMessage(message);
        e.setFilePath(domain.getFilePath());
        e.setFilename(domain.getFileName());
        e.setSize(domain.getSize());
        e.setContentType(domain.getContentType());
        return e;
    }

    public static AttachmentMetaDTO toDto(AttachmentEntity e) {
        if (e == null) return null;
        
        boolean storedInDb = e.getEncryptedBlob() != null && e.getEncryptedBlob().length > 0;
        
        return new AttachmentMetaDTO(
            e.getId(),
            e.getFilename(),
            e.getContentType(),
            e.getSize() != null ? e.getSize() : 0L,
            storedInDb,
            e.getFilePath()
        );
    }

    public static AttachmentMetaDTO domainToDto(AttachmentReference domain) {
        if (domain == null) return null;
        
        return new AttachmentMetaDTO(
            domain.getId(),
            domain.getFileName(),
            domain.getContentType(),
            domain.getSize(),
            domain.isStoredInDb(),
            domain.getFilePath()
        );
    }
}