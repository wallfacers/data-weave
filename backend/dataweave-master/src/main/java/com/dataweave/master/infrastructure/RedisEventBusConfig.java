package com.dataweave.master.infrastructure;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Redis pub/sub 监听容器装配（仅 {@code eventbus.type=redis}）。{@link RedisEventBus} 用它把
 * Redis channel 消息回灌本地 handler。Spring 自动管理容器生命周期（启动/停止）。
 */
@Configuration
@ConditionalOnProperty(name = "eventbus.type", havingValue = "redis")
public class RedisEventBusConfig {

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        return container;
    }
}
