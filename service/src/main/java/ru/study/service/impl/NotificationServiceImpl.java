package ru.study.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.study.core.event.NotificationEvent;
import ru.study.core.event.NotificationLevel;
import ru.study.core.event.bus.EventBus;
import ru.study.service.api.NotificationService;

import java.util.Objects;

public class NotificationServiceImpl implements NotificationService {
    private static final Logger log = LoggerFactory.getLogger(NotificationServiceImpl.class);
    private final EventBus eventBus;

    public NotificationServiceImpl(EventBus eventBus) {
        this.eventBus = Objects.requireNonNull(eventBus);
    }

    @Override
    public void notifyInfo(String message) {
        if (message == null) return;
        log.info(message);
        try { eventBus.publish(new NotificationEvent(NotificationLevel.INFO, message, null)); }
        catch (Exception e) { log.warn("Failed to publish notification event", e); }
    }

    @Override
    public void notifyError(String message, Throwable t) {
        if (message == null && t == null) return;
        log.error(message == null ? "Error" : message, t);
        try { eventBus.publish(new NotificationEvent(NotificationLevel.ERROR, message == null ? t.getMessage() : message, t)); }
        catch (Exception e) { log.warn("Failed to publish notification event", e); }
    }

    @Override
    public void notifySuccess(String message) {
        if (message == null) return;
        log.info("SUCCESS: {}", message);
        try { eventBus.publish(new NotificationEvent(NotificationLevel.SUCCESS, message, null)); }
        catch (Exception e) { log.warn("Failed to publish notification event", e); }
    }
}
