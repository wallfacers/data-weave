package com.dataweave.api.application.bridge;

import com.dataweave.api.application.AguiEvents;
import com.dataweave.master.application.AgentAuditService;
import com.dataweave.master.domain.AgentRun;
import com.dataweave.master.domain.AgentSession;
import com.dataweave.master.domain.AgentStep;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
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

    private static final String INSTRUCTIONS =
            "你是 DataWeave 数据中台的 Agent。平台能力通过 dataweave__* 工具暴露（任务/实例/血缘/指标/诊断查询，"
                    + "建任务、重跑、节点受控执行等写操作）。写操作经平台策略闸门裁决，高风险会返回 PENDING_APPROVAL，"
                    + "需人工在右舷审批卡片批准后续做。";

    private final WorkhorseClient client;
    private final AgentAuditService audit;
    private final AguiEvents events;
    private final ObjectMapper objectMapper;

    public WorkhorseBridge(WorkhorseClient client, AgentAuditService audit,
                           AguiEvents events, ObjectMapper objectMapper) {
        this.client = client;
        this.audit = audit;
        this.events = events;
        this.objectMapper = objectMapper;
    }

    public Flux<ServerSentEvent<String>> run(String threadId, String runId,
                                             String userMessage, String contextSegment) {
        String messageId = UUID.randomUUID().toString();
        AtomicInteger seq = new AtomicInteger(0);
        Map<String, Long> stepIdByToolUse = new ConcurrentHashMap<>();

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
                    .concatMap(ev -> mapEvent(ev, run.getId(), messageId, seq, stepIdByToolUse));

            Flux<ServerSentEvent<String>> trailer = Flux.defer(() -> {
                audit.finishRun(run.getId(), "FINISHED");
                return Flux.just(events.textMessageEnd(messageId), events.runFinished(threadId, runId));
            });

            return Flux.concat(header, body, trailer);
        });
    }

    private Flux<ServerSentEvent<String>> mapEvent(WorkhorseEvent ev, Long runId, String messageId,
                                                   AtomicInteger seq, Map<String, Long> stepIdByToolUse) {
        return switch (ev.type()) {
            case "text" -> Flux.just(events.textMessageContent(messageId, ev.text()));
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
                Map<String, Object> approval = approvalCard(ev.output());
                yield approval == null ? Flux.empty()
                        : Flux.just(events.custom("dataweave.approval", approval));
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
}
