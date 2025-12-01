package ru.study.mailadapter.model;

import java.io.File;
import java.io.InputStream;

public final class OutgoingAttachment {
    private final String filename;
    private final String contentType;
    private final InputStream input; // nullable if file != null
    private final File file; // optional file backing (prefer this for streaming)

    public OutgoingAttachment(String filename, String contentType, InputStream input) {
        this.filename = filename;
        this.contentType = contentType;
        this.input = input;
        this.file = null;
    }

    public OutgoingAttachment(File file, String contentType) {
        this.filename = file == null ? null : file.getName();
        this.contentType = contentType;
        this.input = null;
        this.file = file;
    }

    public String getFilename() { return filename; }
    public String getContentType() { return contentType; }
    public InputStream getInput() { return input; }
    public File getFile() { return file; }
}