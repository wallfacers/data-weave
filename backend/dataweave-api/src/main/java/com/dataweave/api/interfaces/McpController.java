package com.dataweave.api.interfaces;

import com.dataweave.api.application.mcp.McpTool;
import com.dataweave.api.application.mcp.McpToolRegistry;
import com.dataweave.api.application.mcp.ToolResult;
import com.dataweave.api.infrastructure.Locales;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * DataWeave MCP Streamable HTTP 端点（POST JSON-RPC）。手写最小子集（design D8 spike 定案：
 * 不引官方 SDK，Spring Boot 4/Jackson 3 兼容性可控）：initialize / tools/list / tools/call / ping。
 *
 * <p>Bearer 认证由 {@code McpAuthFilter} 前置。阻塞工具调用放 boundedElastic。
 */
@RestController
public class McpController {

    private static final String PROTOCOL_VERSION = "2025-06-18";

    private final McpToolRegistry registry;

    public McpController(McpToolRegistry registry) {
        this.registry = registry;
    }

    @PostMapping(value = "/mcp", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> rpc(@RequestBody Map<String, Object> request,
                                                         ServerWebExchange exchange) {
        Object id = request.get("id");
        String method = str(request.get("method"));
        Locale agentLocale = Locales.agentLocale(exchange.getRequest().getHeaders());

        // 通知（无 id）：不回 body
        if (id == null && method != null && method.startsWith("notifications/")) {
            return Mono.just(ResponseEntity.accepted().build());
        }

        return Mono.fromCallable(() -> ResponseEntity.ok(dispatch(id, method, request, agentLocale)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> dispatch(Object id, String method, Map<String, Object> request, Locale agentLocale) {
        if (method == null) {
            return error(id, -32600, "Invalid Request: missing method");
        }
        return switch (method) {
            case "initialize" -> result(id, Map.of(
                    // 回显客户端请求的协议版本（MCP 协商），缺省回退本服务版本，避免版本不匹配告警。
                    "protocolVersion", clientProtocolVersion(request),
                    "capabilities", Map.of("tools", Map.of()),
                    "serverInfo", Map.of("name", "dataweave", "version", "0.0.1")));
            case "ping" -> result(id, Map.of());
            case "tools/list" -> result(id, Map.of("tools", toolList()));
            case "tools/call" -> {
                Object paramsObj = request.get("params");
                if (!(paramsObj instanceof Map)) {
                    yield error(id, -32602, "Invalid params");
                }
                Map<String, Object> params = (Map<String, Object>) paramsObj;
                String name = str(params.get("name"));
                if (name == null || !registry.has(name)) {
                    yield error(id, -32602, "Unknown tool: " + name);
                }
                Object argsObj = params.get("arguments");
                Map<String, Object> arguments = argsObj instanceof Map
                        ? (Map<String, Object>) argsObj : Map.of();
                ToolResult tr = registry.call(name, arguments, agentLocale);
                Map<String, Object> content = Map.of("type", "text", "text", tr.text());
                Map<String, Object> callResult = new LinkedHashMap<>();
                callResult.put("content", List.of(content));
                callResult.put("isError", tr.isError());
                yield result(id, callResult);
            }
            default -> error(id, -32601, "Method not found: " + method);
        };
    }

    private List<Map<String, Object>> toolList() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (McpTool t : registry.list()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", t.name());
            m.put("description", t.description());
            m.put("inputSchema", t.inputSchema());
            list.add(m);
        }
        return list;
    }

    private Map<String, Object> result(Object id, Object payload) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("result", payload);
        return m;
    }

    private Map<String, Object> error(Object id, int code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("error", Map.of("code", code, "message", message));
        return m;
    }

    @SuppressWarnings("unchecked")
    private String clientProtocolVersion(Map<String, Object> request) {
        Object paramsObj = request.get("params");
        if (paramsObj instanceof Map<?, ?> params) {
            String v = str(((Map<String, Object>) params).get("protocolVersion"));
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return PROTOCOL_VERSION;
    }

    private String str(Object o) {
        return o == null ? null : o.toString();
    }
}
