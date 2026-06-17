package com.dataweave.api.interfaces;

import com.dataweave.api.application.AguiOrchestrator;
import com.dataweave.api.infrastructure.ApiResponse;
import com.dataweave.api.infrastructure.Locales;
import com.dataweave.api.interfaces.dto.RunAgentInput;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;

import java.util.Locale;
import java.util.Map;

/**
 * AG-UI SSE 端点 + 健康检查。
 */
@RestController
public class AguiController {

    private final AguiOrchestrator orchestrator;

    public AguiController(AguiOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    /**
     * AG-UI 对话端点：接收 RunAgentInput，流式返回 AG-UI 事件（每个 event 的 data 是一段 JSON）。
     */
    @PostMapping(value = "/agui", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> agui(@RequestBody RunAgentInput input, ServerWebExchange exchange) {
        Locale agentLocale = Locales.agentLocale(exchange.getRequest().getHeaders());
        return orchestrator.run(input, agentLocale);
    }

    @GetMapping(value = "/api/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Map<String, String>> health() {
        return ApiResponse.ok(Map.of("status", "ok"));
    }
}
