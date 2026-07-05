package com.dataweave.api.infrastructure;

import io.netty.channel.ChannelOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * Spring Boot 4 移除了 WebClient.Builder 的自动配置，必须自建此 bean，
 * 否则任何注入它的 bean 会让上下文启动失败。
 *
 * <p>供分布式任务调度网关（{@code DistributedTaskExecutionGateway}）
 * 与 worker 节点执行网关（{@code WorkerNodeExecGateway}）注入使用。
 *
 * <p>046 dispatch 并行化：WebClient 加响应超时（默认 3s）+ 连接超时（2s）。
 * 去屏障 fire-and-forget 后，慢 worker 不能挂 dispatchExecutor 线程 —— 超时触发
 * {@code onFailure.casRequeue}（DISPATCHED→WAITING）下一轮重派，不阻塞下发池。
 */
@Configuration
public class WebClientConfig {

    @Bean
    public WebClient.Builder webClientBuilder(
            @Value("${scheduler.dispatch-webclient-timeout-ms:3000}") long responseTimeoutMs) {
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofMillis(responseTimeoutMs))
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 2000);
        return WebClient.builder().clientConnector(new ReactorClientHttpConnector(httpClient));
    }
}
