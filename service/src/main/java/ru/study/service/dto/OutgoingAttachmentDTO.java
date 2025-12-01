package ru.study.service.dto;

import lombok.Builder;
import lombok.Value;

import java.io.InputStream;

@Value
@Builder
public class OutgoingAttachmentDTO {
    String fileName;
    String contentType;
    InputStream input;   // nullable: stream provided by caller, adapter must read+close
    String filePath;     // nullable: when set — attachment already stored on disk, prefer file-based streaming
}
