package com.yueyazhishang.paydemo.payment.shared;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Component
public class InMemoryEventBus implements EventBus {
    private final Map<Class<?>, List<Consumer<?>>> handlers = new ConcurrentHashMap<>();

    @Override
    public <T> void publish(T event) {
        List<Consumer<?>> list = handlers.get(event.getClass());
        if (list == null) return;
        for (Consumer handler : list) {
            try {
                handler.accept(event);
            } catch (Exception e) {
                // swallow for demo
            }
        }
    }

    @Override
    public <T> void subscribe(Class<T> clazz, Consumer<T> handler) {
        handlers.computeIfAbsent(clazz, k -> new CopyOnWriteArrayList<>()).add(handler);
    }
}
