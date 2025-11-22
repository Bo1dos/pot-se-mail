package ru.study.core.event;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import ru.study.core.event.bus.EventBus;

public class SimpleEventBus implements EventBus {
    private final Map<Class<?>, List<Consumer<?>>> subscribers = new ConcurrentHashMap<>();

    @Override
    public <T> void subscribe(Class<T> eventType, Consumer<T> handler) {
        subscribers.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(handler);
    }

    @Override
    public <T> void unsubscribe(Class<T> eventType, Consumer<T> handler) {
        List<Consumer<?>> list = subscribers.get(eventType);
        if (list != null) list.remove(handler);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> void publish(T event) {
        List<Consumer<?>> list = subscribers.getOrDefault(event.getClass(), List.of());
        for (Consumer c : list) {
            try {
                c.accept(event);
            } catch (Exception ex) {
                // чтобы не ломать публикацию других подписчиков; логируем/игнорируем
                ex.printStackTrace();
            }
        }
    }

    @Override
    public void shutdown() {
        subscribers.clear();
    }
}
