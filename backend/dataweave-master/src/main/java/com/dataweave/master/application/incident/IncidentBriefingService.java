package com.dataweave.master.application.incident;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.dataweave.master.application.lineage.agent.AgentLineageConfigService;
import com.dataweave.master.application.lineage.agent.LlmChatClient;
import com.dataweave.master.domain.incident.Incident;
import com.dataweave.master.domain.incident.IncidentBriefing;
import com.dataweave.master.domain.incident.IncidentEvent;
import com.dataweave.master.domain.incident.IncidentStats;
import com.dataweave.master.domain.lineage.LineageAgentConfig;
import com.dataweave.master.infrastructure.incident.IncidentBriefingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 069 T031 战况播报：一句话综述 + 完整接班报告（Markdown）。
 *
 * <p><b>SC-010 结构保证</b>：播报文字可滞后（防抖，避免每次事故变动都重外呼 LLM），但接口返回的数字
 * 永远由 {@link IncidentQueryService#stats} 直算 incident 表当下事实——绝不读 incident_briefing.stats_json
 * 佐证快照。这样「首屏播报里的数字」与「feed 里逐条事故」永远同源一致，不会出现播报说 3 个而列表 5 个。
 *
 * <p>防抖用脏集 + 定时抽干：开立/收口/升级只 {@link #markDirty}（O(1) 内存置位），
 * {@link #flushDirty} 每 {@code briefing-debounce-ms} 抽干重生成，把风暴期成百上千次触发合并成每窗口一次外呼。
 * LLM 不可用（未启用/无配置/降级）时退确定性模板综述——播报永不因 AI 缺席而空缺。
 */
@Service
public class IncidentBriefingService {

    private static final Logger log = LoggerFactory.getLogger(IncidentBriefingService.class);
    private static final int REPORT_INCIDENT_LIMIT = 20;

    private final IncidentQueryService queryService;
    private final IncidentBriefingRepository briefingRepo;
    private final AgentLineageConfigService agentConfigService;
    private final LlmChatClient llmChatClient;
    private final IncidentEventPublisher publisher;

    /** 待重生成的项目脏集（去重合并风暴期触发）。 */
    private final Set<ProjectKey> dirty = ConcurrentHashMap.newKeySet();

    public IncidentBriefingService(IncidentQueryService queryService, IncidentBriefingRepository briefingRepo,
                                    AgentLineageConfigService agentConfigService, LlmChatClient llmChatClient,
                                    IncidentEventPublisher publisher) {
        this.queryService = queryService;
        this.briefingRepo = briefingRepo;
        this.agentConfigService = agentConfigService;
        this.llmChatClient = llmChatClient;
        this.publisher = publisher;
    }

    private record ProjectKey(long tenantId, long projectId) {
    }

    /** 播报视图：数字永远实时（stats），综述/报告来自最近一次生成（可滞后）。 */
    public record BriefingView(String summaryLine, IncidentStats stats, String reportMd, LocalDateTime generatedAt) {
    }

    /**
     * GET /api/incidents/briefing 数据源：数字永远现算；综述取最近一次生成，若从未生成过则以确定性模板兜底
     * （保证首屏总有一句话，且与实时数字一致）。
     */
    public BriefingView get(long tenantId, long projectId) {
        IncidentStats stats = queryService.stats(tenantId, projectId);
        Optional<IncidentBriefing> existing = briefingRepo.findByProject(tenantId, projectId);
        if (existing.isPresent()) {
            IncidentBriefing b = existing.get();
            return new BriefingView(b.summaryLine(), stats, b.reportMd(), b.generatedAt());
        }
        return new BriefingView(deterministicSummary(stats), stats, null, null);
    }

    /** 事故开立/收口/升级后调用：只置脏位，真正重生成交给 {@link #flushDirty} 定时抽干（防抖合并）。 */
    public void markDirty(long tenantId, long projectId) {
        dirty.add(new ProjectKey(tenantId, projectId));
    }

    /** 定时抽干脏集：窗口内每项目至多重生成一次，把风暴合并成低频外呼。 */
    @Scheduled(fixedDelayString = "${ops.incident.briefing-debounce-ms:60000}")
    public void flushDirty() {
        for (ProjectKey key : Set.copyOf(dirty)) {
            dirty.remove(key);
            try {
                regenerate(key.tenantId(), key.projectId());
            } catch (Exception e) {
                log.warn("[Briefing] regenerate failed tenant={} project={}: {}", key.tenantId(), key.projectId(),
                        e.toString());
            }
        }
    }

    /**
     * 重生成一份播报并落库 + 广播。综述与报告用 LLM（agent locale）润色，LLM 不可用时退确定性模板；
     * stats_json 仅作生成时点佐证快照落库，接口读取时仍现算（SC-010）。
     */
    public void regenerate(long tenantId, long projectId) {
        IncidentStats stats = queryService.stats(tenantId, projectId);
        List<Incident> open = queryService.snapshot(tenantId, projectId).incidents();
        String deterministic = deterministicSummary(stats);

        String summaryLine = deterministic;
        String reportMd = deterministicReport(stats, open);

        Optional<LineageAgentConfig> cfgOpt = agentConfigService.getActive(tenantId);
        if (cfgOpt.isPresent() && cfgOpt.get().enabled()) {
            LlmChatClient.ChatResult result = llmChatClient.chat(cfgOpt.get(),
                    briefingSystemPrompt(), List.of(new LlmChatClient.ChatMessage("user",
                            briefingUserPrompt(stats, open))));
            if (result.error() == null && result.text() != null && !result.text().isBlank()) {
                String refined = result.text().strip();
                // 综述取首行（≤200 字），完整文本作接班报告
                summaryLine = firstLine(refined, 200);
                reportMd = refined;
            }
        }

        briefingRepo.upsert(tenantId, projectId, summaryLine, reportMd, queryService.statsJson(stats));
        publisher.publish(projectId, new IncidentEvent.BriefingUpdated(summaryLine,
                queryService.statsJson(stats), LocalDateTime.now()));
    }

    // ─── 确定性兜底（LLM 缺席时的综述/报告，也是 LLM 的种子事实）────────────────────────

    private String deterministicSummary(IncidentStats s) {
        if (s.active() == 0) {
            return s.resolvedToday() > 0
                    ? "当前无活跃事故，今日已自动收口 " + s.resolvedToday() + " 起。"
                    : "当前无活跃事故，一切正常。";
        }
        StringBuilder sb = new StringBuilder("当前 ").append(s.active()).append(" 起活跃事故");
        if (s.needsHuman() > 0) sb.append("，").append(s.needsHuman()).append(" 起需人工介入");
        if (s.awaitingApproval() > 0) sb.append("，").append(s.awaitingApproval()).append(" 起待审批");
        if (s.agentWorking() > 0) sb.append("，Agent 正在处理 ").append(s.agentWorking()).append(" 起");
        sb.append("。");
        return sb.toString();
    }

    private String deterministicReport(IncidentStats s, List<Incident> open) {
        StringBuilder md = new StringBuilder();
        md.append("## 接班报告\n\n");
        md.append("- 活跃事故：**").append(s.active()).append("**\n");
        md.append("- 需人工介入：**").append(s.needsHuman()).append("**\n");
        md.append("- 待审批：**").append(s.awaitingApproval()).append("**\n");
        md.append("- Agent 处理中：**").append(s.agentWorking()).append("**\n");
        md.append("- 今日已收口：**").append(s.resolvedToday()).append("**\n");
        if (!open.isEmpty()) {
            md.append("\n### 待办优先\n\n");
            open.stream().limit(REPORT_INCIDENT_LIMIT).forEach(i -> md.append("- `")
                    .append(i.state()).append("` ").append(nullSafe(i.taskDefName()))
                    .append(i.summary() != null ? "：" + i.summary() : "").append("\n"));
        }
        return md.toString();
    }

    private String briefingSystemPrompt() {
        return "你是数据平台的运维值班长。基于给定的事故数字与列表，写一份简洁的中文「接班报告」（Markdown）。"
                + "第一行必须是不超过 60 字的一句话综述（无 Markdown 标记），其后可用列表补充重点与建议。"
                + "只依据给定事实，不臆造；数字以给定为准。";
    }

    private String briefingUserPrompt(IncidentStats s, List<Incident> open) {
        StringBuilder sb = new StringBuilder();
        sb.append("数字：活跃=").append(s.active()).append("，需人工=").append(s.needsHuman())
                .append("，待审批=").append(s.awaitingApproval()).append("，处理中=").append(s.agentWorking())
                .append("，今日已收口=").append(s.resolvedToday()).append("。\n事故列表：\n");
        open.stream().limit(REPORT_INCIDENT_LIMIT).forEach(i -> sb.append("- [").append(i.state()).append("] ")
                .append(nullSafe(i.taskDefName()))
                .append(i.classification() != null ? "（" + i.classification() + "）" : "")
                .append(i.summary() != null ? "：" + i.summary() : "").append("\n"));
        return sb.toString();
    }

    private String firstLine(String text, int max) {
        int nl = text.indexOf('\n');
        String line = nl >= 0 ? text.substring(0, nl).strip() : text.strip();
        // 去掉可能的 Markdown 标题前缀
        line = line.replaceFirst("^#+\\s*", "");
        return line.length() > max ? line.substring(0, max) : line;
    }

    private String nullSafe(String s) {
        return s == null ? "(未命名任务)" : s;
    }
}
