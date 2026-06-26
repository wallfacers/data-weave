package com.dataweave.api.application.bridge;

import com.dataweave.api.application.AguiEvents;
import com.dataweave.master.application.AgentAuditService;
import com.dataweave.master.domain.AgentRun;
import com.dataweave.master.domain.AgentSession;
import com.dataweave.master.domain.AgentStep;
import com.dataweave.master.i18n.Messages;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AG-UI ⇄ workhorse 会话桥（workhorse 模式）。会话映射（agent_session）、消息转发（带上下文段）、
 * SSE 事件 → AG-UI 事件映射 + 审计落库（agent_step：tool_call_start/done、permission_resolved）。
 *
 * <p>workhorse 工具返回 PENDING_APPROVAL 时，桥发 CUSTOM {@code dataweave.approval} 渲染审批卡片。
 */
@Service
public class WorkhorseBridge {

    private static final Logger log = LoggerFactory.getLogger(WorkhorseBridge.class);

    /**
     * MCP 工具名 → Workspace 视图的确定性映射（agent-ui-events spec：workhorse 模式发射规则）。
     * 工具完成即补发 CUSTOM {@code dataweave.ui.open}；未映射的工具不发。
     * 工具名可能带 "&lt;server&gt;__" 前缀（如 dataweave__query_fleet），匹配前先剥掉。
     */
    private static final Map<String, String> TOOL_VIEW = Map.of(
            "query_fleet", "fleet",
            "query_diagnosis", "diagnosis",
            "query_task_definitions", "task-flow",
            "query_task_instances", "task-flow",
            "query_metric", "reports",
            "query_lineage", "lineage",
            "create_task", "task-flow",
            "task_rerun", "task-flow");

    /** 剥掉 MCP server 前缀后查映射；未映射返回 null。 */
    static String viewForTool(String toolName) {
        if (toolName == null) {
            return null;
        }
        int idx = toolName.lastIndexOf("__");
        String bare = idx >= 0 ? toolName.substring(idx + 2) : toolName;
        return TOOL_VIEW.get(bare);
    }

    private static final String INSTRUCTIONS =
            "你是 DataWeave 数据中台的 Agent。平台能力通过 dataweave__* 工具暴露（任务/实例/血缘/指标/诊断查询，"
                    + "建任务、重跑、节点受控执行等写操作）。写操作经平台策略闸门裁决，高风险会返回 PENDING_APPROVAL，"
                    + "需人工在右舷审批卡片批准后续做。";

    private final WorkhorseClient client;
    private final AgentAuditService audit;
    private final AguiEvents events;
    private final ObjectMapper objectMapper;
    private final Messages messages;

    public WorkhorseBridge(WorkhorseClient client, AgentAuditService audit,
                           AguiEvents events, ObjectMapper objectMapper, Messages messages) {
        this.client = client;
        this.audit = audit;
        this.events = events;
        this.objectMapper = objectMapper;
        this.messages = messages;
    }

    public Flux<ServerSentEvent<String>> run(String threadId, String runId,
                                             String userMessage, String contextSegment, Locale locale) {
        String messageId = UUID.randomUUID().toString();
        AtomicInteger seq = new AtomicInteger(0);
        Map<String, Long> stepIdByToolUse = new ConcurrentHashMap<>();
        Map<String, String> toolNameByUse = new ConcurrentHashMap<>();

        return Flux.defer(() -> {
            AgentSession session = audit.getOrCreateSession(threadId, "WORKHORSE", null);
            String whSessionId = session.getWorkhorseSessionId();
            if (whSessionId == null) {
                whSessionId = client.createSession(INSTRUCTIONS, Map.of("conversationId", threadId));
                audit.getOrCreateSession(threadId, "WORKHORSE", whSessionId);
            }
            AgentRun run = audit.startRun(session.getId(), runId, "USER_MESSAGE", userMessage);
            String message = (contextSegment == null || contextSegment.isBlank())
                    ? userMessage : contextSegment + "\n" + userMessage;

            Flux<ServerSentEvent<String>> header = Flux.just(
                    events.runStarted(threadId, runId),
                    events.textMessageStart(messageId));

            Flux<ServerSentEvent<String>> body = client.sendMessage(whSessionId, message)
                    .concatMap(ev -> mapEvent(ev, run.getId(), messageId, seq, stepIdByToolUse,
                            toolNameByUse, locale))
                    // submit/stream 任何错误（自愈后仍 409、404、连接异常…）都透出一条通用报错并干净收尾，
                    // 绝不让 Flux 报错传播导致连接重置（浏览器 ERR_INCOMPLETE_CHUNKED_ENCODING）→ 聊天空白。
                    // 详细错误原因写日志，仅向用户暴露泛化消息，避免异常原文泄露内部路径/栈/DB 细节。
                    .onErrorResume(err -> {
                        log.warn("Workhorse stream error for messageId={}: {}", messageId, rootMessage(err));
                        return Flux.just(events.textMessageContent(messageId,
                                messages.get("agent.workhorse.error.generic", locale)));
                    });

            Flux<ServerSentEvent<String>> trailer = Flux.defer(() -> {
                audit.finishRun(run.getId(), "FINISHED");
                return Flux.just(events.textMessageEnd(messageId), events.runFinished(threadId, runId));
            });

            return Flux.concat(header, body, trailer);
        });
    }

    /**
     * Headless oneshot：起一个临时 workhorse 会话发一条消息，聚合全部 {@code text} 增量为最终文本返回，
     * 不经前端 SSE、不落对话审计。供后台诊断（{@code WorkhorseDiagnosisAnalyzer}）等非对话流复用。
     *
     * @param instructions 系统指令（如「你是诊断分析器，只输出 JSON」）
     * @param message      用户消息（诊断上下文 prompt）
     */
    public Mono<String> runHeadless(String instructions, String message) {
        // metadata 值须为字符串：workhorse /v1/sessions 对非字符串值（如 boolean）返回 400。
        return Mono.fromCallable(() -> client.createSession(instructions, Map.of("headless", "true")))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(sessionId -> client.sendMessage(sessionId, message))
                .filter(ev -> "text".equals(ev.type()) && ev.text() != null)
                .map(WorkhorseEvent::text)
                .collect(java.util.stream.Collectors.joining())
                .map(String::trim);
    }

    private Flux<ServerSentEvent<String>> mapEvent(WorkhorseEvent ev, Long runId, String messageId,
                                                   AtomicInteger seq, Map<String, Long> stepIdByToolUse,
                                                   Map<String, String> toolNameByUse, Locale locale) {
        return switch (ev.type()) {
            case "text" -> Flux.just(events.textMessageContent(messageId, ev.text()));
            case "error" -> {
                // LLM/上游报错（key 失效、限流、超时、中断…）：输出泛化本地化报错文本，
                // 而非静默吞掉变成空回复。详细错误写日志供排查，避免泄露上游内部细节。
                String detail = ev.text() == null ? "" : ev.text();
                log.warn("Workhorse upstream error for messageId={}: {}", messageId,
                        truncate(detail, 1000));
                yield Flux.just(events.textMessageContent(messageId,
                        messages.get("agent.workhorse.error.generic", locale)));
            }
            case "tool_call_start" -> {
                AgentStep step = new AgentStep();
                step.setRunId(runId);
                step.setSeq(seq.incrementAndGet());
                step.setToolUseId(ev.toolUseId());
                step.setToolName(ev.toolName());
                step.setInputJson(ev.inputJson());
                AgentStep saved = audit.recordStep(step);
                if (ev.toolUseId() != null) {
                    stepIdByToolUse.put(ev.toolUseId(), saved.getId());
                    if (ev.toolName() != null) {
                        toolNameByUse.put(ev.toolUseId(), ev.toolName());
                    }
                }
                yield Flux.empty();
            }
            case "tool_call_done" -> {
                updateStep(ev.toolUseId(), stepIdByToolUse, step -> {
                    step.setOutputPreview(truncate(ev.output(), 4000));
                    step.setTruncated(ev.truncated() ? 1 : 0);
                    if (ev.output() != null) {
                        step.setOutputBytes(ev.output().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
                    }
                });
                java.util.List<ServerSentEvent<String>> out = new java.util.ArrayList<>();
                Map<String, Object> approval = approvalCard(ev.output());
                if (approval != null) {
                    out.add(events.custom("dataweave.approval", approval));
                }
                String view = ev.toolUseId() == null ? null
                        : viewForTool(toolNameByUse.get(ev.toolUseId()));
                if (view != null) {
                    out.add(events.custom("dataweave.ui.open", Map.of("view", view)));
                }
                yield Flux.fromIterable(out);
            }
            case "permission_resolved" -> {
                updateStep(ev.toolUseId(), stepIdByToolUse, step -> {
                    step.setDecision(ev.decision());
                    step.setDecisionSource(ev.source());
                });
                yield Flux.empty();
            }
            default -> Flux.empty();
        };
    }

    private void updateStep(String toolUseId, Map<String, Long> stepIdByToolUse,
                            java.util.function.Consumer<AgentStep> mutator) {
        Long stepId = toolUseId == null ? null : stepIdByToolUse.get(toolUseId);
        if (stepId == null) {
            return;
        }
        audit.getStep(stepId).ifPresent(step -> {
            mutator.accept(step);
            step.setUpdatedAt(LocalDateTime.now());
            audit.recordStep(step);
        });
    }

    /** 工具输出若为 PENDING_APPROVAL，解析成审批卡片负载；否则 null。 */
    private Map<String, Object> approvalCard(String output) {
        if (output == null || !output.contains("PENDING_APPROVAL")) {
            return null;
        }
        try {
            Map<String, Object> m = objectMapper.readValue(output, new TypeReference<Map<String, Object>>() {
            });
            if (!"PENDING_APPROVAL".equals(String.valueOf(m.get("outcome")))) {
                return null;
            }
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("approvalId", m.get("approvalId"));
            card.put("level", m.get("level"));
            card.put("summary", m.get("summary"));
            card.put("message", m.get("message"));
            card.put("requiresConfirmation", m.getOrDefault("requiresConfirmation", false));
            return card;
        } catch (Exception e) {
            return null;
        }
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 从异常链取一条简洁可读的报错信息透给用户（HTTP body 优先，回退异常 message/类名）。 */
    private String rootMessage(Throwable err) {
        Throwable t = err;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String msg = t.getMessage();
        return (msg == null || msg.isBlank()) ? t.getClass().getSimpleName() : truncate(msg, 500);
    }
}
