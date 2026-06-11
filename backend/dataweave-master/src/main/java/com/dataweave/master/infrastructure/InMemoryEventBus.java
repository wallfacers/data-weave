package com.dataweave.master.infrastructure;

import com.dataweave.master.domain.EventBus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * {@link EventBus} 的内存实现（all-in-one 默认）：进程内同步派发，发布即触发订阅者。
 *
 * <p>单 JVM 内 master 与 worker 同进程，唤醒事件无需跨网络。distributed 模式由 Redis pub/sub 实现替换
 * （{@code eventbus.type=redis}）。
 */
@Component
@ConditionalOnProperty(name = "eventbus.type", havingValue = "memory", matchIfMissing = true)
public class InMemoryEventBus implements EventBus {

    private final Map<String, List<Consumer<String>>> handlers = new ConcurrentHashMap<>();

    @Override
    public void publish(String channel, String message) {
        List<Consumer<String>> list = handlers.get(channel);
        if (list == null) {
            return;
        }
        for (Consumer<String> h : list) {
            h.accept(message);
        }
    }

    @Override
    public Subscription subscribe(String channel, Consumer<String> handler) {
        handlers.computeIfAbsent(channel, k -> new CopyOnWriteArrayList<>()).add(handler);
        return () -> {
            List<Consumer<String>> list = handlers.get(channel);
            if (list != null) {
                list.remove(handler);
            }
        };
    }
}
