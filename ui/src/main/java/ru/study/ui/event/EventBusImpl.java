package ru.study.ui.event;

import javafx.application.Platform;
import ru.study.core.event.bus.EventBus;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class EventBusImpl implements EventBus {
    private final Map<Class<?>, List<Consumer<?>>> subscribers = new ConcurrentHashMap<>();

    public <T> void subscribe(Class<T> eventType, Consumer<T> handler) {
        subscribers.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(handler);
    }

    public <T> void unsubscribe(Class<T> eventType, Consumer<T> handler) {
        List<Consumer<?>> list = subscribers.get(eventType);
        if (list != null) list.remove(handler);
    }

    // publish in UI thread (if currently not on FX thread)
    @SuppressWarnings("unchecked")
    public <T> void publish(T event) {
        List<Consumer<?>> list = subscribers.getOrDefault(event.getClass(), List.of());
        if (list.isEmpty()) return;
        if (Platform.isFxApplicationThread()) {
            for (Consumer c : list) c.accept(event);
        } else {
            Platform.runLater(() -> {
                for (Consumer c : list) c.accept(event);
            });
        }
    }

    public void shutdown() {
        subscribers.clear();
    }
}
