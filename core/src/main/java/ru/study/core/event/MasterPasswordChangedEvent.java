package ru.study.core.event;

import java.time.Instant;

public final class MasterPasswordChangedEvent {
    private final Instant when = Instant.now();
    public MasterPasswordChangedEvent() {}
    public Instant when() { return when; }
}
