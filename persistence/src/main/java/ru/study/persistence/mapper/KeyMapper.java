package ru.study.persistence.mapper;

import ru.study.core.model.KeyReference;
import ru.study.persistence.entity.KeyEntity;
import ru.study.persistence.util.MapperUtils;

public final class KeyMapper {
    private KeyMapper() {}

    public static KeyReference toDomain(KeyEntity e) {
        if (e == null) return null;
        Long accountId = e.getAccount() == null ? null : e.getAccount().getId();
        return new KeyReference(
            e.getId(),
            accountId,
            e.getId() == null ? null : "key-" + e.getId(), // or fingerprint
            e.getPublicKeyPem(),
            e.getPrivateKeyBlob() != null && e.getPrivateKeyBlob().length > 0,
            MapperUtils.toInstant(e.getCreatedAt())
        );
    }

    public static KeyEntity toEntity(KeyReference d) {
        if (d == null) return null;
        KeyEntity e = new KeyEntity();
        e.setId(d.getId());
        e.setPublicKeyPem(d.getPublicKeyPem());
        e.setPrivateKeyBlob(null); // set when saving private key
        e.setKeyIv(null);
        e.setKeySalt(null);
        e.setCreatedAt(MapperUtils.toOffsetDateTime(d.getCreatedAt()));
        return e;
    }
}
