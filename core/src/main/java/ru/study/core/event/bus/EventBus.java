package ru.study.core.event.bus;

import java.util.function.Consumer;

public interface EventBus {
    public <T> void subscribe(Class<T> eventType, Consumer<T> handler);
    public <T> void unsubscribe(Class<T> eventType, Consumer<T> handler);
    public <T> void publish(T event);
    public void shutdown();
}
