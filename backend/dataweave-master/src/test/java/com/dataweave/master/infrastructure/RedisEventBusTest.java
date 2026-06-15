package com.dataweave.master.infrastructure;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * RedisEventBus 跨进程唤醒闭环：subscribe 时必须向 Redis 监听容器注册 channel 监听，
 * 否则 publish 的 wake 没人消费、调度退化成纯轮询（吞吐崩塌的根因）。
 */
@ExtendWith(MockitoExtension.class)
class RedisEventBusTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private RedisMessageListenerContainer container;

    private RedisEventBus bus;

    @BeforeEach
    void setUp() {
        bus = new RedisEventBus(redisTemplate, container);
    }

    @Test
    void subscribe_registersRedisChannelListener() {
        bus.subscribe("dw:wake", m -> {});
        verify(container).addMessageListener(any(MessageListener.class), eq(new ChannelTopic("dw:wake")));
    }

    @Test
    void onMessage_dispatchesToLocalHandler() {
        List<String> got = new ArrayList<>();
        bus.subscribe("dw:wake", got::add);
        bus.onMessage("dw:wake", "report");
        assertThat(got).containsExactly("report");
    }

    @Test
    void multipleSubscribersSameChannel_registerContainerOnce_allReceive() {
        List<String> a = new ArrayList<>();
        List<String> b = new ArrayList<>();
        bus.subscribe("dw:wake", a::add);
        bus.subscribe("dw:wake", b::add);
        // Redis 监听对同一 channel 只注册一次，避免重复消费
        verify(container, times(1)).addMessageListener(any(MessageListener.class), eq(new ChannelTopic("dw:wake")));
        bus.onMessage("dw:wake", "x");
        assertThat(a).containsExactly("x");
        assertThat(b).containsExactly("x");
    }
}
