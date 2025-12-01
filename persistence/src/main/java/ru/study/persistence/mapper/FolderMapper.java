package ru.study.persistence.mapper;

import ru.study.core.dto.FolderDTO;
import ru.study.core.model.Folder;
import ru.study.persistence.entity.FolderEntity;

public final class FolderMapper {
    private FolderMapper() {}

    public static Folder toDomain(FolderEntity e) {
        if (e == null) return null;
        String lastSync = e.getLastSyncUid() == null ? null : String.valueOf(e.getLastSyncUid());
        return new Folder(e.getId(), e.getAccountId(), e.getServerName(), e.getLocalName(), lastSync);
    }

    public static FolderEntity toEntity(Folder d) {
        if (d == null) return null;
        FolderEntity e = new FolderEntity();
        e.setId(d.getId());
        e.setAccountId(d.getAccountId());
        e.setServerName(d.getServerName());
        e.setLocalName(d.getLocalName());

        if (d.getLastSyncUid() == null || d.getLastSyncUid().isBlank()) {
            e.setLastSyncUid(null);
        } else {
            try {
                e.setLastSyncUid(Long.parseLong(d.getLastSyncUid()));
            } catch (NumberFormatException ex) {
                e.setLastSyncUid(null);
            }
        }
        return e;
    }

    public static FolderDTO toDto(Folder d) {
        if (d == null) return null;
        Long lastSync = d.getLastSyncUid() == null ? null : Long.valueOf(d.getLastSyncUid());
        return new FolderDTO(d.getId(), d.getAccountId(), d.getServerName(), d.getLocalName(), lastSync);
    }
}