package com.dataweave.master.infrastructure;

import com.dataweave.master.domain.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * {@link EventBus} 的 Redis pub/sub 实现（design D2/D10 / task 3.8）。
 *
 * <p>跨 master 实例广播唤醒事件。发布到 Redis channel {@code dw:wake}。
 * 配置 {@code eventbus.type=redis} 激活。
 *
 * <p>Redis 全部丢失时系统退化为兜底轮询，正确性无损。
 */
@Component
@ConditionalOnProperty(name = "eventbus.type", havingValue = "redis")
public class RedisEventBus implements EventBus {

    private static final Logger log = LoggerFactory.getLogger(RedisEventBus.class);

    private final StringRedisTemplate redisTemplate;
    private final Map<String, List<Consumer<String>>> handlers = new ConcurrentHashMap<>();

    public RedisEventBus(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void publish(String channel, String message) {
        try {
            redisTemplate.convertAndSend(channel, message);
        } catch (Exception e) {
            log.warn("[RedisEventBus] 发布失败（退化轮询兜底）：channel={}, error={}", channel, e.getMessage());
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

    /** Redis 消息到达时分发给本地 handler。由 RedisMessageListener 回调。 */
    public void onMessage(String channel, String message) {
        List<Consumer<String>> list = handlers.get(channel);
        if (list != null) {
            for (Consumer<String> h : list) {
                h.accept(message);
            }
        }
    }
}
