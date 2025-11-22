package ru.study.core.model;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

@Builder
@Getter
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public final class AttachmentReference {
    @EqualsAndHashCode.Include
    private final Long id;
    @EqualsAndHashCode.Include
    private final String filePath;
    private final String fileName;
    private final long size;
    private final boolean storedInDb;

    public AttachmentReference(Long id, String filePath, String fileName, long size, boolean storedInDb) {
        if (filePath == null && id == null) {
            throw new IllegalArgumentException("Either filePath or id must be provided");
        }
        this.id = id;
        this.filePath = filePath;
        this.fileName = fileName;
        this.size = size;
        this.storedInDb = storedInDb;
    }
}