package com.dataweave.api.application;

import com.dataweave.api.application.bridge.WorkhorseBridge;
import com.dataweave.api.interfaces.dto.PageContext;
import com.dataweave.api.interfaces.dto.RunAgentInput;
import com.dataweave.master.application.AgentAuditService;
import com.dataweave.master.domain.AgentRun;
import com.dataweave.master.domain.AgentSession;
import com.dataweave.master.domain.AgentStep;
import com.dataweave.master.i18n.Messages;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * AG-UI 事件编排器：按 {@code agent.mode} 路由（mock = IntentRouter，workhorse = WorkhorseBridge），
 * 两模式经同一 {@link AguiEvents} 出口产出同构事件序列，全部留痕（agent_run/agent_step）。
 *
 * <p>事件序列：RUN_STARTED → TEXT_MESSAGE_START → N×TEXT_MESSAGE_CONTENT → TEXT_MESSAGE_END
 * → [CUSTOM] → RUN_FINISHED。
 */
@Service
public class AguiOrchestrator {

    private final IntentRouter intentRouter;
    private final WorkhorseBridge workhorseBridge;
    private final AgentAuditService audit;
    private final AguiEvents events;
    private final Messages messages;
    private final String mode;

    public AguiOrchestrator(IntentRouter intentRouter,
                            WorkhorseBridge workhorseBridge,
                            AgentAuditService audit,
                            AguiEvents events,
                            Messages messages,
                            @Value("${agent.mode:mock}") String mode) {
        this.intentRouter = intentRouter;
        this.workhorseBridge = workhorseBridge;
        this.audit = audit;
        this.events = events;
        this.messages = messages;
        this.mode = mode;
    }

    public Flux<ServerSentEvent<String>> run(RunAgentInput input, Locale locale) {
        String threadId = input.getThreadId() != null ? input.getThreadId() : UUID.randomUUID().toString();
        String runId = input.getRunId() != null ? input.getRunId() : UUID.randomUUID().toString();
        String userMessage = input.lastUserContent();
        PageContext context = input.pageContext();
        List<RunAgentInput.Attachment> attachments = input.attachments();

        if ("workhorse".equalsIgnoreCase(mode)) {
            // 附件段拼在页面上下文段之后，一并喂给真脑（实体引用 + 文件名，文件可经 /api/chat/files/{id} 回取）。
            String segment = joinSegments(context.toPromptSegment(locale, messages),
                    attachmentSegment(attachments, locale));
            return workhorseBridge.run(threadId, runId, userMessage, segment)
                    .subscribeOn(Schedulers.boundedElastic());
        }

        // mock 模式：IntentRouter 路径 + 同样留痕
        return Flux.defer(() -> {
                    AgentSession session = audit.getOrCreateSession(threadId, "MOCK", null);
                    AgentRun runRow = audit.startRun(session.getId(), runId, "USER_MESSAGE", userMessage);
                    AgentReply reply = withAttachmentAck(intentRouter.route(userMessage, context, locale),
                            attachments, locale);
                    recordMockStep(runRow.getId(), userMessage, reply);
                    audit.finishRun(runRow.getId(), "FINISHED");
                    return Flux.fromIterable(buildEvents(threadId, runId, UUID.randomUUID().toString(), reply));
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private void recordMockStep(Long runId, String userMessage, AgentReply reply) {
        AgentStep step = new AgentStep();
        step.setRunId(runId);
        step.setSeq(1);
        step.setToolName(reply.customEventName() != null ? reply.customEventName() : "intent.text");
        step.setInputJson(truncate(userMessage, 4000));
        step.setOutputPreview(truncate(reply.markdown(), 4000));
        step.setTruncated(0);
        audit.recordStep(step);
    }

    /** 拼附件本地化上下文段，如 {@code [附件] 任务=etl_daily 文件=error.log}；无附件返回空串。 */
    private String attachmentSegment(List<RunAgentInput.Attachment> attachments, Locale locale) {
        if (attachments == null || attachments.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(messages.get("agent.attach.prefix", locale));
        for (RunAgentInput.Attachment a : attachments) {
            sb.append(" ").append(messages.get(attachmentKey(a), locale, a.displayLabel()));
        }
        return sb.toString();
    }

    private static String attachmentKey(RunAgentInput.Attachment a) {
        if (a.isFile()) {
            return "agent.attach.file";
        }
        return switch (a.refType() == null ? "" : a.refType()) {
            case "task" -> "agent.attach.task";
            case "instance" -> "agent.attach.instance";
            case "finding" -> "agent.attach.finding";
            case "datasource" -> "agent.attach.datasource";
            default -> "agent.attach.file";
        };
    }

    /** mock 模式：附件非空时在回复前置一句本地化「已收到附件」，证明附件已抵达后端（保留结构化/视图召唤）。 */
    private AgentReply withAttachmentAck(AgentReply reply, List<RunAgentInput.Attachment> attachments, Locale locale) {
        if (attachments == null || attachments.isEmpty()) {
            return reply;
        }
        String labels = attachments.stream()
                .map(RunAgentInput.Attachment::displayLabel)
                .reduce((x, y) -> x + ", " + y).orElse("");
        String ack = messages.get("agent.attach.ack", locale, attachments.size(), labels);
        return new AgentReply(ack + "\n\n" + reply.markdown(), reply.structured(),
                reply.customEventName(), reply.uiOpen());
    }

    private static String joinSegments(String a, String b) {
        if (a == null || a.isBlank()) {
            return b == null ? "" : b;
        }
        if (b == null || b.isBlank()) {
            return a;
        }
        return a + " " + b;
    }

    private List<ServerSentEvent<String>> buildEvents(String threadId, String runId,
                                                      String messageId, AgentReply reply) {
        List<ServerSentEvent<String>> out = new ArrayList<>();
        out.add(events.runStarted(threadId, runId));
        out.add(events.textMessageStart(messageId));
        for (String delta : splitDeltas(reply.markdown())) {
            out.add(events.textMessageContent(messageId, delta));
        }
        out.add(events.textMessageEnd(messageId));
        if (reply.structured() != null) {
            String eventName = reply.customEventName() != null ? reply.customEventName() : "dataweave.result";
            out.add(events.custom(eventName, reply.structured()));
        }
        if (reply.uiOpen() != null) {
            out.add(events.custom("dataweave.ui.open", uiOpenPayload(reply.uiOpen())));
        }
        out.add(events.runFinished(threadId, runId));
        return out;
    }

    /** dataweave.ui.open 事件载荷：{ view, params? }。 */
    static java.util.Map<String, Object> uiOpenPayload(AgentReply.UiOpen uiOpen) {
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("view", uiOpen.view());
        if (uiOpen.params() != null && !uiOpen.params().isEmpty()) {
            payload.put("params", uiOpen.params());
        }
        return payload;
    }

    /** 把完整 Markdown 切成若干 delta 片段（按行/段），模拟流式增量。 */
    private List<String> splitDeltas(String markdown) {
        List<String> deltas = new ArrayList<>();
        if (markdown == null || markdown.isEmpty()) {
            deltas.add("");
            return deltas;
        }
        String[] lines = markdown.split("(?<=\n)");
        StringBuilder buf = new StringBuilder();
        for (String line : lines) {
            buf.append(line);
            if (buf.length() >= 60 || line.endsWith("\n\n")) {
                deltas.add(buf.toString());
                buf.setLength(0);
            }
        }
        if (buf.length() > 0) {
            deltas.add(buf.toString());
        }
        if (deltas.isEmpty()) {
            deltas.add(markdown);
        }
        return deltas;
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
