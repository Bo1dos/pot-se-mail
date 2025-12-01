package ru.study.core.event;

public final class NotificationEvent {
    private final NotificationLevel level;
    private final String message;
    private final Throwable throwable;

    public NotificationEvent(NotificationLevel level, String message, Throwable throwable) {
        this.level = level;
        this.message = message;
        this.throwable = throwable;
    }

    public NotificationLevel level() { return level; }
    public String message() { return message; }
    public Throwable throwable() { return throwable; }
}
