package com.dataweave.api.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Spring Boot 4 移除了 WebClient.Builder 的自动配置，必须自建此 bean，
 * 否则任何注入它的 bean 会让上下文启动失败。
 *
 * <p>供分布式任务调度网关（{@code DistributedTaskExecutionGateway}）
 * 与 worker 节点执行网关（{@code WorkerNodeExecGateway}）注入使用。
 */
@Configuration
public class WebClientConfig {

    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }
}
