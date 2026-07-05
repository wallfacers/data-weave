package com.dataweave.api.infrastructure;

import io.netty.channel.ChannelOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * Spring Boot 4 移除了 WebClient.Builder 的自动配置，必须自建此 bean，
 * 否则任何注入它的 bean 会让上下文启动失败。
 *
 * <p>收尾拆分（046 曾把 dispatch 的 3s 响应超时设在唯一 builder 上，波及全部消费方）：
 * <ul>
 *   <li><b>默认 {@code webClientBuilder}</b>：通用消费方（alert webhook 等），宽松响应超时
 *       （默认 30s，防 {@code block()} 永挂）+ 连接超时 2s。</li>
 *   <li><b>{@code dispatchWebClientBuilder}</b>：仅 {@code DistributedTaskExecutionGateway} 注入。
 *       046 dispatch 并行化：响应超时默认 3s —— 去屏障 fire-and-forget 后，慢 worker 不能挂
 *       dispatchExecutor 线程，超时触发 {@code onFailure.casRequeue}（DISPATCHED→WAITING）
 *       下一轮重派，不阻塞下发池。</li>
 * </ul>
 */
@Configuration
public class WebClientConfig {

    @Bean
    @Primary
    public WebClient.Builder webClientBuilder(
            @Value("${webclient.default-response-timeout-ms:30000}") long responseTimeoutMs) {
        return builderWithTimeout(responseTimeoutMs);
    }

    @Bean("dispatchWebClientBuilder")
    public WebClient.Builder dispatchWebClientBuilder(
            @Value("${scheduler.dispatch-webclient-timeout-ms:3000}") long responseTimeoutMs) {
        return builderWithTimeout(responseTimeoutMs);
    }

    private WebClient.Builder builderWithTimeout(long responseTimeoutMs) {
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofMillis(responseTimeoutMs))
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 2000);
        return WebClient.builder().clientConnector(new ReactorClientHttpConnector(httpClient));
    }
}
