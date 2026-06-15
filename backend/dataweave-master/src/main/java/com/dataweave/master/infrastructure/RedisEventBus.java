package com.dataweave.master.infrastructure;

import com.dataweave.master.domain.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * {@link EventBus} 的 Redis pub/sub 实现（design D2/D10 / task 3.8）。
 *
 * <p>跨 master 实例广播唤醒事件。发布到 Redis channel {@code dw:wake}；本进程 subscribe 时向
 * {@link RedisMessageListenerContainer} 注册该 channel 的监听，Redis 消息到达即回灌 {@link #onMessage}
 * 分发给本地 handler——这样 publish 的 wake 才会真正驱动各 master 即时补轮（缺这一环则退化纯轮询）。
 * 配置 {@code eventbus.type=redis} 激活。
 *
 * <p>Redis 全部丢失时系统退化为兜底轮询，正确性无损。
 */
@Component
@ConditionalOnProperty(name = "eventbus.type", havingValue = "redis")
public class RedisEventBus implements EventBus {

    private static final Logger log = LoggerFactory.getLogger(RedisEventBus.class);

    private final StringRedisTemplate redisTemplate;
    private final RedisMessageListenerContainer listenerContainer;
    private final Map<String, List<Consumer<String>>> handlers = new ConcurrentHashMap<>();
    /** 已向 Redis 容器注册监听的 channel，确保每 channel 只注册一次（避免重复消费）。 */
    private final Set<String> redisListenedChannels = ConcurrentHashMap.newKeySet();

    public RedisEventBus(StringRedisTemplate redisTemplate, RedisMessageListenerContainer listenerContainer) {
        this.redisTemplate = redisTemplate;
        this.listenerContainer = listenerContainer;
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
        // 首个订阅该 channel 时，向 Redis 监听容器注册一次：消息到达回灌 onMessage 分发本地 handler。
        if (redisListenedChannels.add(channel)) {
            listenerContainer.addMessageListener(
                    (message, pattern) -> onMessage(channel, new String(message.getBody(), StandardCharsets.UTF_8)),
                    new ChannelTopic(channel));
        }
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
