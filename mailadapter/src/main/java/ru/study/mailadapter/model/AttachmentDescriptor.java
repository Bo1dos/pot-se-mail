package ru.study.mailadapter.model;

public final class AttachmentDescriptor {
    private final String id;        // unique per message (e.g. "part-2")
    private final String fileName;
    private final String contentType;
    private final long size;        // -1 if unknown
    private final int partIndex;    // index of MIME part used by adapter to open stream

    public AttachmentDescriptor(String id, String fileName, String contentType, long size, int partIndex) {
        this.id = id;
        this.fileName = fileName;
        this.contentType = contentType;
        this.size = size;
        this.partIndex = partIndex;
    }

    public String getId() { return id; }
    public String getFileName() { return fileName; }
    public String getContentType() { return contentType; }
    public long getSize() { return size; }
    public int getPartIndex() { return partIndex; }
}
