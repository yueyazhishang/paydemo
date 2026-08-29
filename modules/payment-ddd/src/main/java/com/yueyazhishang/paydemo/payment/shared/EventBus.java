package com.yueyazhishang.paydemo.payment.shared;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public interface EventBus {
    <T> void publish(T event);
    <T> void subscribe(Class<T> clazz, Consumer<T> handler);
}
