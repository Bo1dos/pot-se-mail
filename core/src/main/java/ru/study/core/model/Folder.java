package ru.study.core.model;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

@Builder
@Getter
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public final class Folder {
    @EqualsAndHashCode.Include
    private final Long id;
    private final Long accountId;
    private final String serverName;
    private final String localName;
    private final Long lastSyncUid;

    public Folder(Long id, Long accountId, String serverName, String localName, Long lastSyncUid) {
        this.id = id;
        this.accountId = accountId;
        this.serverName = serverName;
        this.localName = localName == null ? serverName : localName;
        this.lastSyncUid = lastSyncUid;
    }
}