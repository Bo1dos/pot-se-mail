package ru.study.service.dto;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class SyncResult {
    int newMessages;
    List<String> errors; // human-readable errors
}
