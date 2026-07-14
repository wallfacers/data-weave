package com.dataweave.master.application.incident;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.dataweave.master.application.ActionRequest;
import com.dataweave.master.application.GateResult;
import com.dataweave.master.application.GatedActionService;
import com.dataweave.master.application.lineage.agent.AgentLineageConfigService;
import com.dataweave.master.application.lineage.agent.LlmChatClient;
import com.dataweave.master.domain.TaskDef;
import com.dataweave.master.domain.incident.Incident;
import com.dataweave.master.domain.incident.IncidentEvent;
import com.dataweave.master.domain.incident.IncidentMessage;
import com.dataweave.master.domain.incident.IncidentStates;
import com.dataweave.master.domain.incident.MessageKinds;
import com.dataweave.master.domain.lineage.LineageAgentConfig;
import com.dataweave.master.i18n.BizException;
import com.dataweave.master.infrastructure.incident.IncidentMessageRepository;
import com.dataweave.master.infrastructure.incident.IncidentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 067 T030 事故线程自由对话：监督席在事故线程内向运维 Agent 发问/下指令。
 *
 * <p>时序：HUMAN_SAY 立即落库+广播（人看到自己发言回显）→ 异步后台线程组装上下文（系统提示 + 证据包 +
 * 线程历史，按预算截断）→ {@link LlmChatClient#streamChat} 流式外呼，逐段广播 {@code delta}（streamId 串联）→
 * 完成后落 AGENT_SAY（payload 带 streamId，前端据此以落库消息替换打字流分片，SC-005 只信持久化层）。
 *
 * <p>结构化动作：回复尾部可选携带 ```action {json}``` 块，仅白名单动作
 * （rerun/adjust_resources/reverify）经统一闸门执行并落 ACTION 消息；escalate 走内部状态转移；
 * 其余（含 publish_fix 及任何未知类型）一律丢弃并记录——对话不得成为绕过闸门的旁路。
 */
@Service
public class IncidentConversationService {

    private static final Logger log = LoggerFactory.getLogger(IncidentConversationService.class);
    private static final int HISTORY_LIMIT = 40;
    private static final int HISTORY_CHAR_BUDGET = 6000;
    private static final int LOG_TAIL_BUDGET = 4000;
    private static final Pattern ACTION_BLOCK =
            Pattern.compile("```action\\s*(\\{.*?})\\s*```", Pattern.DOTALL);

    private final IncidentRepository incidentRepo;
    private final IncidentMessageRepository messageRepo;
    private final IncidentEventPublisher publisher;
    private final IncidentEvidenceCollector evidenceCollector;
    private final AgentLineageConfigService agentConfigService;
    private final LlmChatClient llmChatClient;
    private final GatedActionService gatedActionService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService chatPool = Executors.newFixedThreadPool(2,
            r -> new Thread(r, "incident-chat"));

    public IncidentConversationService(IncidentRepository incidentRepo, IncidentMessageRepository messageRepo,
                                        IncidentEventPublisher publisher, IncidentEvidenceCollector evidenceCollector,
                                        AgentLineageConfigService agentConfigService, LlmChatClient llmChatClient,
                                        GatedActionService gatedActionService) {
        this.incidentRepo = incidentRepo;
        this.messageRepo = messageRepo;
        this.publisher = publisher;
        this.evidenceCollector = evidenceCollector;
        this.agentConfigService = agentConfigService;
        this.llmChatClient = llmChatClient;
        this.gatedActionService = gatedActionService;
    }

    /**
     * 提交一条人类发言并触发 Agent 异步回复。校验：事故归属、非收口、智能运维已启用（否则对话无意义）。
     * 返回已落库的 HUMAN_SAY 消息（供接口即时回显；Agent 回复经 SSE 直播流达）。
     */
    public IncidentMessage chat(long tenantId, long projectId, UUID incidentId, String text, String actor) {
        Incident inc = incidentRepo.findById(incidentId)
                .filter(i -> i.tenantId() == tenantId && i.projectId() == projectId)
                .orElseThrow(() -> new BizException("incident.not_found", incidentId));
        if (IncidentStates.isTerminal(inc.state())) {
            throw new BizException("incident.closed");
        }
        if (!agentConfigService.isOpsEnabledFor(tenantId)) {
            throw new BizException("incident.agent_disabled");
        }
        String userText = text == null ? "" : text.strip();
        if (userText.isEmpty()) {
            throw new BizException("incident.chat_empty");
        }
        IncidentMessage human = messageRepo.append(incidentId, MessageKinds.HUMAN_SAY, userText, null,
                actor != null && !actor.isBlank() ? actor : "user");
        broadcastMessage(inc.projectId(), incidentId, human);
        chatPool.submit(() -> {
            try {
                respond(inc, userText);
            } catch (Exception e) {
                log.warn("[IncidentChat] respond failed incidentId={}: {}", incidentId, e.toString());
            }
        });
        return human;
    }

    /** 后台：组装上下文 → 流式外呼 → 落 AGENT_SAY → 解析并执行白名单动作。 */
    private void respond(Incident inc, String userText) {
        Optional<LineageAgentConfig> cfgOpt = agentConfigService.getActive(inc.tenantId());
        if (cfgOpt.isEmpty() || !cfgOpt.get().opsEnabled()) {
            var sys = messageRepo.append(inc.id(), MessageKinds.SYSTEM, "智能运维未启用，无法回复", null, "system");
            broadcastMessage(inc.projectId(), inc.id(), sys);
            return;
        }
        IncidentEvidenceCollector.Evidence evidence = null;
        try {
            evidence = evidenceCollector.collect(inc.latestInstanceId());
        } catch (Exception e) {
            log.warn("[IncidentChat] evidence collect failed incidentId={}: {}", inc.id(), e.toString());
        }
        String locale = evidence != null && evidence.failedInstance() != null
                && evidence.failedInstance().getLocale() != null ? evidence.failedInstance().getLocale() : "zh-CN";

        String streamId = UUID.randomUUID().toString();
        List<LlmChatClient.ChatMessage> history = buildHistory(inc);
        LlmChatClient.ChatResult result = llmChatClient.streamChat(cfgOpt.get(),
                systemPrompt(locale), buildMessages(inc, evidence, history, userText),
                delta -> publisher.publish(inc.projectId(), new IncidentEvent.Delta(inc.id(), streamId, delta)));

        if (result.error() != null && (result.text() == null || result.text().isBlank())) {
            var sys = messageRepo.append(inc.id(), MessageKinds.SYSTEM, "AI 回复失败：" + result.error(), null, "system");
            broadcastMessage(inc.projectId(), inc.id(), sys);
            return;
        }
        String replyText = result.text() == null ? "" : result.text();
        // 落 AGENT_SAY（正文剥掉 action 块，只留人读叙述；payload 带 streamId 供前端替换打字流）
        String narrative = ACTION_BLOCK.matcher(replyText).replaceAll("").strip();
        String payload = toJson(Map.of("streamId", streamId));
        var say = messageRepo.append(inc.id(), MessageKinds.AGENT_SAY,
                narrative.isEmpty() ? replyText : narrative, payload, "ops-agent");
        broadcastMessage(inc.projectId(), inc.id(), say);

        parseAndRunAction(inc, replyText);
    }

    /** 解析回复尾部可选 action 块并按白名单执行；识别失败/非白名单/无块一律安全跳过。 */
    private void parseAndRunAction(Incident inc, String replyText) {
        Matcher m = ACTION_BLOCK.matcher(replyText);
        if (!m.find()) return;
        JsonNode node;
        try {
            node = objectMapper.readTree(m.group(1));
        } catch (Exception e) {
            log.info("[IncidentChat] unparseable action block dropped incidentId={}", inc.id());
            return;
        }
        String type = node.hasNonNull("type") ? node.get("type").asString() : "";
        switch (type) {
            case "rerun" -> submitGated(inc, "incident_rerun", "INCIDENT_RERUN", "TASK_INSTANCE",
                    inc.latestInstanceId().toString(), null, "对话指令：重跑");
            case "reverify" -> submitGated(inc, "incident_reverify", "INCIDENT_REVERIFY", "TASK_INSTANCE",
                    inc.latestInstanceId().toString(), null, "对话指令：复验");
            case "adjust_resources" -> runAdjustResources(inc, node);
            default -> {
                // escalate / publish_fix / 未知类型：对话不得成为绕过既有编排的旁路，一律丢弃并记录
                log.info("[IncidentChat] non-whitelisted action '{}' dropped incidentId={}", type, inc.id());
                var sys = messageRepo.append(inc.id(), MessageKinds.SYSTEM,
                        "Agent 建议的动作「" + type + "」不在对话可直接执行的白名单内，已忽略（请用线程内按钮或等待自动处置）",
                        null, "system");
                broadcastMessage(inc.projectId(), inc.id(), sys);
            }
        }
    }

    private void runAdjustResources(Incident inc, JsonNode node) {
        Integer memoryMb = node.hasNonNull("memoryMb") ? node.get("memoryMb").asInt() : null;
        Integer cpuCores = node.hasNonNull("cpuCores") ? node.get("cpuCores").asInt() : null;
        if (memoryMb == null && cpuCores == null) {
            log.info("[IncidentChat] adjust_resources without targets dropped incidentId={}", inc.id());
            return;
        }
        String command = toJson(Map.of(
                "instanceId", inc.latestInstanceId().toString(),
                "memoryMb", memoryMb == null ? 0 : memoryMb,
                "cpuCores", cpuCores == null ? 0 : cpuCores));
        submitGated(inc, "incident_adjust_resources", "INCIDENT_ADJUST_RESOURCES", "TASK",
                String.valueOf(inc.taskDefId()), command, "对话指令：调整资源后重跑");
    }

    private void submitGated(Incident inc, String toolName, String actionType, String targetType,
                              String targetId, String command, String summary) {
        ActionRequest req = ActionRequest.builder()
                .toolName(toolName).actionType(actionType)
                .targetType(targetType).targetId(targetId)
                .command(command)
                .actor("ops-agent").actorSource("AGENT")
                .summary(summary + "（事故 " + inc.id() + "）")
                .build();
        GateResult result = gatedActionService.submit(req);
        boolean ok = result.executed() && result.resultInstanceId() != null;
        String content = ok ? (summary + "：已执行")
                : (summary + "：未生效（" + result.outcome() + "）" + (result.message() != null ? "：" + result.message() : ""));
        String payload = toJson(Map.of("outcome", result.outcome().name(),
                "actionId", result.actionId() == null ? -1 : result.actionId()));
        var msg = messageRepo.append(inc.id(), MessageKinds.ACTION, content, payload, "ops-agent");
        broadcastMessage(inc.projectId(), inc.id(), msg);
    }

    // ─── prompt 组装 ─────────────────────────────────────────

    private String systemPrompt(String locale) {
        String lang = locale != null && locale.startsWith("en") ? "English" : "简体中文";
        return "你是数据平台的运维值班 Agent，正在与人类监督员就一起任务失败事故对话。"
                + "依据给定的事故证据（任务定义、失败日志、诊断结论）如实回答，不臆造。用 " + lang + " 回复，简洁专业。"
                + "若且仅若监督员明确要求执行处置，可在回复末尾附一个动作块："
                + "```action\n{\"type\":\"rerun\"}\n``` 或 "
                + "{\"type\":\"reverify\"} 或 {\"type\":\"adjust_resources\",\"memoryMb\":4096,\"cpuCores\":2}。"
                + "不要主动附动作块；不确定时只回答不附动作。";
    }

    private List<LlmChatClient.ChatMessage> buildMessages(Incident inc, IncidentEvidenceCollector.Evidence evidence,
                                                           List<LlmChatClient.ChatMessage> history, String userText) {
        StringBuilder ctx = new StringBuilder();
        ctx.append("# 事故上下文\n");
        ctx.append("任务：").append(inc.taskDefName() == null ? "(未命名)" : inc.taskDefName()).append("\n");
        ctx.append("状态：").append(inc.state()).append("\n");
        if (inc.classification() != null) ctx.append("分型：").append(inc.classification()).append("\n");
        if (inc.summary() != null) ctx.append("摘要：").append(inc.summary()).append("\n");
        if (inc.suggestion() != null) ctx.append("建议：").append(inc.suggestion()).append("\n");
        if (evidence != null) {
            TaskDef td = evidence.taskDef();
            if (td != null && td.getContent() != null) {
                ctx.append("\n## 任务脚本\n```\n").append(truncate(td.getContent(), HISTORY_CHAR_BUDGET)).append("\n```\n");
            }
            if (evidence.logTail() != null) {
                ctx.append("\n## 失败日志（尾部）\n```\n").append(truncate(evidence.logTail(), LOG_TAIL_BUDGET)).append("\n```\n");
            }
        }
        java.util.List<LlmChatClient.ChatMessage> msgs = new java.util.ArrayList<>();
        msgs.add(new LlmChatClient.ChatMessage("user", ctx.toString()));
        msgs.addAll(history);
        msgs.add(new LlmChatClient.ChatMessage("user", userText));
        return msgs;
    }

    /** 线程历史（近若干条 AGENT_SAY/HUMAN_SAY），映射为对话轮次，按字符预算从新到旧截断。 */
    private List<LlmChatClient.ChatMessage> buildHistory(Incident inc) {
        List<IncidentMessage> recent = messageRepo.findAfter(inc.id(), 0, HISTORY_LIMIT);
        java.util.List<LlmChatClient.ChatMessage> out = new java.util.ArrayList<>();
        int budget = HISTORY_CHAR_BUDGET;
        for (int i = recent.size() - 1; i >= 0; i--) {
            IncidentMessage msg = recent.get(i);
            String role;
            if (MessageKinds.HUMAN_SAY.equals(msg.kind())) role = "user";
            else if (MessageKinds.AGENT_SAY.equals(msg.kind())) role = "assistant";
            else continue; // 只喂对话轮，跳过 step/action/proposal/system 噪声
            String content = msg.content() == null ? "" : msg.content();
            if (content.length() > budget) break;
            budget -= content.length();
            out.add(0, new LlmChatClient.ChatMessage(role, content));
        }
        // 末条即本次 HUMAN_SAY（已落库），避免与 buildMessages 追加的 userText 重复：去掉最后一条 user
        if (!out.isEmpty() && "user".equals(out.get(out.size() - 1).role())) {
            out.remove(out.size() - 1);
        }
        return out;
    }

    private void broadcastMessage(long projectId, UUID incidentId, IncidentMessage msg) {
        publisher.publish(projectId, new IncidentEvent.MessageAppended(incidentId, msg));
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(s.length() - max) : s;
    }
}
