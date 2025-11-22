package ru.study.persistence.mapper;

import ru.study.core.dto.AttachmentMetaDTO;
import ru.study.core.model.AttachmentReference;
import ru.study.persistence.entity.AttachmentEntity;

public final class AttachmentMapper {
    private AttachmentMapper() {}

    public static AttachmentReference toDomain(AttachmentEntity e) {
        if (e == null) return null;
        return new AttachmentReference(
            e.getId(),
            e.getFilePath(),
            e.getFilename(),
            e.getSize() == null ? 0L : e.getSize(),
            e.getEncryptedBlob() == null || e.getEncryptedBlob().length == 0 ? false : true // crude
        );
    }

    public static AttachmentEntity toEntity(AttachmentReference d) {
        if (d == null) return null;
        AttachmentEntity e = new AttachmentEntity();
        e.setId(d.getId());
        e.setFilePath(d.getFilePath());
        e.setFilename(d.getFileName());
        e.setSize(d.getSize());
        e.setContentType(null); // TODO: set if available
        // encryptedBlob/iv handled elsewhere when saving file
        return e;
    }

    public static AttachmentMetaDTO toDto(AttachmentEntity e) {
        if (e == null) return null;
        return new AttachmentMetaDTO(
            e.getId(),
            e.getFilename(),
            e.getContentType(),
            e.getSize(),
            e.getEncryptedBlob() == null || e.getEncryptedBlob().length == 0 ? Boolean.FALSE : Boolean.TRUE,
            e.getFilePath()
        );
    }

    public static AttachmentMetaDTO domainToDto(AttachmentReference d) {
        if (d == null) return null;
        return new AttachmentMetaDTO(d.getId(), d.getFileName(), null, d.getSize(), d.isStoredInDb(), d.getFilePath());
    }
}
