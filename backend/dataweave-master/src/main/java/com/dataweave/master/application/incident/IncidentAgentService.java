package com.dataweave.master.application.incident;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.dataweave.master.application.ActionRequest;
import com.dataweave.master.application.ApprovalService;
import com.dataweave.master.application.GateResult;
import com.dataweave.master.application.GatedActionService;
import com.dataweave.master.application.TaskService;
import com.dataweave.master.application.lineage.agent.AgentLineageConfigService;
import com.dataweave.master.application.lineage.agent.LlmChatClient;
import com.dataweave.master.domain.Checkpoint;
import com.dataweave.master.domain.InstanceStates;
import com.dataweave.master.domain.TaskDef;
import com.dataweave.master.domain.TaskDefRepository;
import com.dataweave.master.domain.TaskDefVersion;
import com.dataweave.master.domain.TaskDefVersionRepository;
import com.dataweave.master.domain.TaskInstance;
import com.dataweave.master.domain.TaskInstanceRepository;
import com.dataweave.master.domain.incident.Incident;
import com.dataweave.master.domain.incident.IncidentClassifications;
import com.dataweave.master.domain.incident.IncidentEvent;
import com.dataweave.master.domain.incident.IncidentProposal;
import com.dataweave.master.domain.incident.IncidentStates;
import com.dataweave.master.domain.incident.MessageKinds;
import com.dataweave.master.domain.incident.ProposalStatuses;
import com.dataweave.master.domain.lineage.LineageAgentConfig;
import com.dataweave.master.i18n.BizException;
import com.dataweave.master.infrastructure.CheckpointRepository;
import com.dataweave.master.infrastructure.incident.IncidentMessageRepository;
import com.dataweave.master.infrastructure.incident.IncidentProposalRepository;
import com.dataweave.master.infrastructure.incident.IncidentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 067 运维 Agent 编排（US1 诊断段）：CAS 认领 → 采证（chip 直播）→ 确定性凭据指纹前置 → LLM 分型诊断
 * → 结论落库+推进 ACTING。全部经确定性 Java 代码编排，LLM 只做分型判断与文本生成（research R1）。
 * 处置/验证段属 US2/US3（RemediationPlanner 及后续），本类只负责把事故从 OPEN 推进到「已诊断待处置」。
 */
@Service
public class IncidentAgentService {

    private static final Logger log = LoggerFactory.getLogger(IncidentAgentService.class);

    private final IncidentRepository incidentRepo;
    private final IncidentMessageRepository messageRepo;
    private final IncidentEventPublisher publisher;
    private final IncidentEvidenceCollector evidenceCollector;
    private final DiagnosisPrompt diagnosisPrompt;
    private final LlmChatClient llmChatClient;
    private final AgentLineageConfigService agentConfigService;
    private final RemediationPlanner planner;
    private final GatedActionService gatedActionService;
    private final TaskInstanceRepository instanceRepository;
    private final CheckpointRepository checkpointRepository;
    private final FixProposalPrompt fixProposalPrompt;
    private final IncidentProposalRepository proposalRepository;
    private final TaskDefRepository taskDefRepository;
    private final TaskDefVersionRepository taskDefVersionRepository;
    private final TaskService taskService;
    private final ApprovalService approvalService;
    private final IncidentBriefingService briefingService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TokenBucket rateLimiter;

    public IncidentAgentService(IncidentRepository incidentRepo, IncidentMessageRepository messageRepo,
                                 IncidentEventPublisher publisher, IncidentEvidenceCollector evidenceCollector,
                                 DiagnosisPrompt diagnosisPrompt, LlmChatClient llmChatClient,
                                 AgentLineageConfigService agentConfigService,
                                 RemediationPlanner planner, GatedActionService gatedActionService,
                                 TaskInstanceRepository instanceRepository, CheckpointRepository checkpointRepository,
                                 FixProposalPrompt fixProposalPrompt, IncidentProposalRepository proposalRepository,
                                 TaskDefRepository taskDefRepository, TaskDefVersionRepository taskDefVersionRepository,
                                 TaskService taskService, ApprovalService approvalService,
                                 IncidentBriefingService briefingService,
                                 @Value("${ops.incident.llm-rate-per-min:30}") int llmRatePerMin) {
        this.incidentRepo = incidentRepo;
        this.messageRepo = messageRepo;
        this.publisher = publisher;
        this.evidenceCollector = evidenceCollector;
        this.diagnosisPrompt = diagnosisPrompt;
        this.llmChatClient = llmChatClient;
        this.agentConfigService = agentConfigService;
        this.planner = planner;
        this.gatedActionService = gatedActionService;
        this.instanceRepository = instanceRepository;
        this.checkpointRepository = checkpointRepository;
        this.fixProposalPrompt = fixProposalPrompt;
        this.proposalRepository = proposalRepository;
        this.taskDefRepository = taskDefRepository;
        this.taskDefVersionRepository = taskDefVersionRepository;
        this.taskService = taskService;
        this.approvalService = approvalService;
        this.briefingService = briefingService;
        this.rateLimiter = new TokenBucket(llmRatePerMin);
    }

    /**
     * 对一个事故执行诊断。CAS 认领（OPEN|DIAG_UNAVAILABLE → ANALYZING）失败即安全退出——
     * 覆盖巡检器重复提交/并发提交的场景，绝不重复外呼。永不抛异常（内部各阶段失败均降级 DIAG_UNAVAILABLE）。
     */
    public void diagnose(UUID incidentId) {
        Incident inc = incidentRepo.findById(incidentId).orElse(null);
        if (inc == null) return;
        boolean claimed = incidentRepo.casState(incidentId, IncidentStates.OPEN, IncidentStates.ANALYZING)
                || incidentRepo.casState(incidentId, IncidentStates.DIAG_UNAVAILABLE, IncidentStates.ANALYZING);
        if (!claimed) return;
        broadcastIncident(incidentId);
        briefingService.markDirty(inc.tenantId(), inc.projectId()); // 开单进入处理面：播报置脏（防抖后重生成）

        boolean opsEnabled = agentConfigService.isOpsEnabledFor(inc.tenantId());
        Optional<LineageAgentConfig> cfgOpt = opsEnabled ? agentConfigService.getActive(inc.tenantId()) : Optional.empty();
        if (cfgOpt.isEmpty() || !cfgOpt.get().opsEnabled()) {
            degradeUnavailable(incidentId, "智能运维未启用或未配置 AI Agent");
            return;
        }
        LineageAgentConfig cfg = cfgOpt.get();

        thinking(inc.projectId(), incidentId, "START", "正在采集失败证据");
        IncidentEvidenceCollector.Evidence evidence;
        try {
            evidence = evidenceCollector.collect(inc.latestInstanceId());
        } catch (Exception e) {
            log.warn("[IncidentAgent] evidence collection failed incidentId={}: {}", incidentId, e.toString());
            degradeUnavailable(incidentId, "证据采集失败：" + e.getMessage());
            return;
        }
        chip(inc.projectId(), incidentId, "evidence", "读取失败日志", "DONE");
        thinking(inc.projectId(), incidentId, "STOP", null);

        if (CredentialFingerprint.matches(evidence.logTail())) {
            chip(inc.projectId(), incidentId, "fingerprint", "命中确定性凭据故障指纹", "DONE");
            applyDiagnosis(incidentId, IncidentClassifications.CONFIG_CREDENTIAL, "HIGH",
                    List.of("日志命中确定性凭据故障指纹"),
                    "请检查该任务绑定数据源的凭据配置是否正确，修正后可在事故线程内触发复验。");
            return;
        }

        if (!rateLimiter.tryAcquire()) {
            degradeUnavailable(incidentId, "运维 Agent 调用频率超限，本轮跳过（下轮巡检重试）");
            return;
        }

        chip(inc.projectId(), incidentId, "code", "分析代码与运行历史", "RUNNING");
        thinking(inc.projectId(), incidentId, "START", "正在分析日志与代码");
        String locale = evidence.failedInstance().getLocale() != null
                ? evidence.failedInstance().getLocale() : "zh-CN";
        LlmChatClient.ChatResult result = llmChatClient.chat(cfg, diagnosisPrompt.systemPrompt(locale),
                List.of(new LlmChatClient.ChatMessage("user", diagnosisPrompt.userPrompt(evidence))));
        thinking(inc.projectId(), incidentId, "STOP", null);
        if (result.error() != null || result.text() == null) {
            chip(inc.projectId(), incidentId, "code", "分析代码与运行历史", "FAILED");
            degradeUnavailable(incidentId, "AI 端点调用失败：" + result.error());
            return;
        }
        chip(inc.projectId(), incidentId, "code", "分析代码与运行历史", "DONE");

        DiagnosisPrompt.Diagnosis diag = diagnosisPrompt.parse(result.text());
        String suggestion = diag.suggestion();
        if ((suggestion == null || suggestion.isBlank()) && IncidentClassifications.isNonSelfHealable(diag.classification())) {
            // FR-008「100% 附根因定位与可执行操作建议」保底：LLM 未产出可用建议时，绝不能让升级人工却给不出指引。
            suggestion = IncidentClassifications.defaultSuggestion(diag.classification());
        }
        applyDiagnosis(incidentId, diag.classification(), diag.confidence(), diag.evidenceLines(), suggestion);
    }

    private void applyDiagnosis(UUID incidentId, String classification, String confidence,
                                 List<String> evidenceLines, String suggestion) {
        String summary = classification + (suggestion != null && !suggestion.isBlank()
                ? "：" + truncate(suggestion, 200) : "");
        boolean ok = incidentRepo.applyDiagnosis(incidentId, IncidentStates.ANALYZING, IncidentStates.ACTING,
                classification, confidence, summary, suggestion);
        if (!ok) return;
        String payload = toJson(Map.of(
                "classification", classification,
                "confidence", confidence,
                "evidenceLines", evidenceLines == null ? List.of() : evidenceLines));
        var msg = messageRepo.append(incidentId, MessageKinds.AGENT_STEP, "诊断完成：" + summary, payload, "ops-agent");
        broadcastMessageAndIncident(incidentId, msg);
        actOrVerify(incidentId);
    }

    /**
     * 067 US2/US3 处置与验证段：对处于 ACTING 的事故决定「验证已处置结果」或「决策下一步处置动作」。
     * 由 {@link #applyDiagnosis} 诊断刚完成后调用一次（首次处置），也由 {@link IncidentSweeper} 每轮对所有
     * ACTING 事故重试调用（追踪处置后 latest_instance 终态，成功收口/失败进入下一梯度，T022 验证收口循环）。
     * 永不抛异常——内部各阶段失败均转 NEEDS_HUMAN（宁可保守升级人工，不可静默卡死事故）。
     */
    public void actOrVerify(UUID incidentId) {
        Incident inc = incidentRepo.findById(incidentId).orElse(null);
        if (inc == null || !IncidentStates.ACTING.equals(inc.state())) return;

        TaskInstance latest = instanceRepository.findById(inc.latestInstanceId()).orElse(null);
        if (latest == null) {
            escalate(inc, "最新失败实例已不存在，转人工介入");
            return;
        }
        String state = latest.getState();

        // 修复提案已发布待验证：优先按提案验证结果收尾（成功收口/失败回滚+转人工），绝不重新走 planner
        // 再生成第二份提案（防循环红线）。同一事故任意时刻至多一个 PUBLISHED 提案在飞。
        Optional<IncidentProposal> inFlight = proposalRepository.findByIncident(inc.id()).stream()
                .filter(p -> ProposalStatuses.PUBLISHED.equals(p.status())).findFirst();
        if (inFlight.isPresent()) {
            verifyPublishedProposal(inc, inFlight.get(), state);
            return;
        }

        if (InstanceStates.SUCCESS.equals(state)) {
            closeResolved(inc, resolveCloseKind(inc));
            return;
        }
        if (!InstanceStates.isTerminal(state) && !InstanceStates.SUSPENDED.equals(state)) {
            return; // 处置中的重跑仍在 WAITING/DISPATCHED/RUNNING，本轮无需动作
        }

        IncidentEvidenceCollector.Evidence evidence;
        try {
            evidence = evidenceCollector.collect(inc.latestInstanceId());
        } catch (Exception e) {
            escalate(inc, "复验采证失败：" + e.getMessage());
            return;
        }
        Integer curMemoryMb = null, curCpuCores = null;
        TaskDef taskDef = evidence.taskDef();
        if (taskDef != null && taskDef.getResourcesJson() != null && !taskDef.getResourcesJson().isBlank()) {
            curMemoryMb = jsonInt(taskDef.getResourcesJson(), "memoryMb");
            curCpuCores = jsonInt(taskDef.getResourcesJson(), "cpuCores");
        }
        RemediationPlanner.Decision decision = planner.plan(inc.classification(), inc.autoActionCount(),
                evidence.streaming(), evidence.hasAvailableCheckpoint(), curMemoryMb, curCpuCores);

        switch (decision.kind()) {
            case RERUN -> submitAction(inc, latest, decision, "incident_rerun", "INCIDENT_RERUN", null);
            case RESUME_CHECKPOINT -> submitResumeCheckpoint(inc, latest, decision);
            case ADJUST_RESOURCES -> submitAdjustResources(inc, latest, decision, taskDef);
            case PROPOSE_FIX -> proposeFix(inc, decision, evidence, taskDef);
            case ESCALATE -> escalate(inc, decision.reason());
        }
    }

    /**
     * 已发布修复提案的验证收尾：SUCCESS→标记 VERIFIED+自动收口；仍在跑→本轮无需动作；
     * 再次失败→标记 VERIFY_FAILED + 回滚至基线版本（新快照，版本只进不退）+ 转人工（绝不再生成第二份提案）。
     */
    private void verifyPublishedProposal(Incident inc, IncidentProposal proposal, String latestState) {
        if (InstanceStates.SUCCESS.equals(latestState)) {
            proposalRepository.markVerified(proposal.id(), true);
            closeResolved(inc, resolveCloseKind(inc));
            return;
        }
        if (!InstanceStates.isTerminal(latestState) && !InstanceStates.SUSPENDED.equals(latestState)) {
            return; // 验证重跑仍在 WAITING/DISPATCHED/RUNNING
        }
        proposalRepository.markVerified(proposal.id(), false);
        rollbackToBase(proposal);
        escalate(inc, "修复提案验证失败，已回滚至基线版本，转人工介入");
    }

    /** 回滚也是新快照（版本只进不退）：把基线版本内容写回 task_def 并落新版本。 */
    private void rollbackToBase(IncidentProposal proposal) {
        TaskDef task = taskDefRepository.findById(proposal.taskDefId()).orElse(null);
        if (task == null) return;
        TaskDefVersion baseVer = taskDefVersionRepository
                .findByTaskIdAndVersionNo(proposal.taskDefId(), proposal.baseVersionNo()).orElse(null);
        if (baseVer == null) return;
        task.setContent(baseVer.getContent());
        task.setResourcesJson(baseVer.getResourcesJson());
        taskDefRepository.save(task);
        int rollbackVersionNo = taskService.writeTaskVersionSnapshot(task, null,
                "067 修复验证失败自动回滚至基线版本（事故 " + proposal.incidentId() + "）");
        proposalRepository.markRolledBack(proposal.id(), rollbackVersionNo);
    }

    /** 通用重跑/复验动作提交（rerun 与 reverify 底层同一 OpsService 操作，仅 toolName/actionType 语义不同）。 */
    private void submitAction(Incident inc, TaskInstance latest, RemediationPlanner.Decision decision,
                               String toolName, String actionType, String command) {
        if (!instanceStillNeedsRemediation(inc, latest)) return;
        ActionRequest req = ActionRequest.builder()
                .toolName(toolName).actionType(actionType)
                .targetType("TASK_INSTANCE").targetId(inc.latestInstanceId().toString())
                .command(command)
                .actor("ops-agent").actorSource("AGENT")
                .summary("运维 Agent 自动处置（事故 " + inc.id() + "）：" + decision.reason())
                .build();
        GateResult result = gatedActionService.submit(req);
        applyGateResult(inc, decision, result);
    }

    private void submitResumeCheckpoint(Incident inc, TaskInstance latest, RemediationPlanner.Decision decision) {
        Optional<Checkpoint> cp = checkpointRepository.findLatestSuccess(inc.latestInstanceId());
        if (cp.isEmpty()) {
            escalate(inc, "决策续跑但未找到可用检查点，转人工介入");
            return;
        }
        String command = toJson(Map.of("checkpointId", cp.get().id().toString()));
        submitAction(inc, latest, decision, "incident_resume_checkpoint", "INCIDENT_RESUME_CHECKPOINT", command);
    }

    private void submitAdjustResources(Incident inc, TaskInstance latest, RemediationPlanner.Decision decision,
                                        TaskDef taskDef) {
        if (taskDef == null) {
            escalate(inc, "任务定义已被删除，无法调整资源，转人工介入");
            return;
        }
        if (!instanceStillNeedsRemediation(inc, latest)) return;
        String command = toJson(Map.of(
                "instanceId", inc.latestInstanceId().toString(),
                "memoryMb", decision.newMemoryMb(),
                "cpuCores", decision.newCpuCores()));
        ActionRequest req = ActionRequest.builder()
                .toolName("incident_adjust_resources").actionType("INCIDENT_ADJUST_RESOURCES")
                .targetType("TASK").targetId(String.valueOf(taskDef.getId()))
                .command(command)
                .actor("ops-agent").actorSource("AGENT")
                .summary("运维 Agent 调整资源后重跑（事故 " + inc.id() + "）：" + decision.reason())
                .build();
        GateResult result = gatedActionService.submit(req);
        applyGateResult(inc, decision, result);
    }

    /**
     * CODE 分型（T023）：LLM 生成全量修复内容+change_summary → 落 incident_proposal(PENDING) →
     * 经闸门提交 incident_publish_fix（L3，需人审）→ 事故 ACTING→AWAITING_APPROVAL + PROPOSAL 消息。
     * 任一阶段失败均转人工介入（不可自愈、绝不留事故卡死在 ACTING）。
     */
    private void proposeFix(Incident inc, RemediationPlanner.Decision decision,
                             IncidentEvidenceCollector.Evidence evidence, TaskDef taskDef) {
        if (taskDef == null) {
            escalate(inc, "任务定义已被删除，无法生成修复提案，转人工介入");
            return;
        }
        boolean opsEnabled = agentConfigService.isOpsEnabledFor(inc.tenantId());
        Optional<LineageAgentConfig> cfgOpt = opsEnabled ? agentConfigService.getActive(inc.tenantId()) : Optional.empty();
        if (cfgOpt.isEmpty() || !cfgOpt.get().opsEnabled()) {
            escalate(inc, "智能运维未启用或未配置 AI Agent，无法生成修复提案，转人工介入");
            return;
        }
        if (!rateLimiter.tryAcquire()) {
            return; // 本轮跳过，下轮巡检重试（事故仍处 ACTING，不落消息避免刷屏）
        }
        LineageAgentConfig cfg = cfgOpt.get();

        chip(inc.projectId(), inc.id(), "proposal", "生成修复提案", "RUNNING");
        thinking(inc.projectId(), inc.id(), "START", "正在生成修复提案");
        String locale = evidence.failedInstance().getLocale() != null ? evidence.failedInstance().getLocale() : "zh-CN";
        LlmChatClient.ChatResult result = llmChatClient.chat(cfg, fixProposalPrompt.systemPrompt(locale),
                List.of(new LlmChatClient.ChatMessage("user",
                        fixProposalPrompt.userPrompt(taskDef, evidence, inc.suggestion()))));
        thinking(inc.projectId(), inc.id(), "STOP", null);
        if (result.error() != null || result.text() == null) {
            chip(inc.projectId(), inc.id(), "proposal", "生成修复提案", "FAILED");
            escalate(inc, "AI 生成修复提案失败：" + result.error());
            return;
        }
        Optional<FixProposalPrompt.FixProposal> parsed = fixProposalPrompt.parse(result.text());
        if (parsed.isEmpty()) {
            chip(inc.projectId(), inc.id(), "proposal", "生成修复提案", "FAILED");
            escalate(inc, "AI 生成的修复提案无法解析为有效脚本，转人工介入");
            return;
        }
        chip(inc.projectId(), inc.id(), "proposal", "生成修复提案", "DONE");

        int baseVersionNo = taskDef.getCurrentVersionNo() != null ? taskDef.getCurrentVersionNo() : 0;
        String evidenceJson = toJson(Map.of(
                "classification", inc.classification() == null ? "" : inc.classification(),
                "confidence", inc.confidence() == null ? "" : inc.confidence(),
                "suggestion", inc.suggestion() == null ? "" : inc.suggestion()));
        UUID proposalId = proposalRepository.insert(inc.id(), taskDef.getId(), baseVersionNo,
                parsed.get().content(), parsed.get().changeSummary(), evidenceJson);

        ActionRequest req = ActionRequest.builder()
                .toolName("incident_publish_fix").actionType("INCIDENT_PUBLISH_FIX")
                .targetType("INCIDENT_PROPOSAL").targetId(proposalId.toString())
                .command(toJson(Map.of("incidentId", inc.id().toString())))
                .actor("ops-agent").actorSource("AGENT")
                .summary("运维 Agent 生成代码修复提案（事故 " + inc.id() + "）：" + decision.reason())
                .build();
        GateResult gateResult = gatedActionService.submit(req);
        if (gateResult.actionId() != null) {
            proposalRepository.linkAgentAction(proposalId, gateResult.actionId());
        }

        boolean cas = incidentRepo.casState(inc.id(), IncidentStates.ACTING, IncidentStates.AWAITING_APPROVAL);
        if (!cas) return;
        String content = "已生成修复提案，待人工审批：" + truncate(parsed.get().changeSummary(), 200);
        String payload = toJson(Map.of("proposalId", proposalId.toString(), "taskDefId", taskDef.getId(),
                "baseVersionNo", baseVersionNo, "changeSummary", parsed.get().changeSummary()));
        var msg = messageRepo.append(inc.id(), MessageKinds.PROPOSAL, content, payload, "ops-agent");
        broadcastMessageAndIncident(inc.id(), msg);
    }

    /**
     * 批准修复提案（T023）：底层复用 {@link ApprovalService}（L3 二次确认）→ 触发 {@code incident_publish_fix}
     * 执行器完成基线陈旧校验 + 发布 + 重跑验证。提案必须仍为 PENDING（已过期/已处理拒绝重复批准）。
     */
    public ApprovalService.ApprovalResult approveProposal(UUID incidentId, UUID proposalId, String approver,
                                                            String confirmation, java.util.Locale locale) {
        IncidentProposal proposal = proposalRepository.findById(proposalId)
                .filter(p -> incidentId.equals(p.incidentId()))
                .orElseThrow(() -> new BizException("incident.proposal_not_found", proposalId));
        if (proposal.agentActionId() == null) {
            throw new BizException("incident.proposal_not_pending", proposalId);
        }
        if (!ProposalStatuses.PENDING.equals(proposal.status())) {
            String code = ProposalStatuses.STALE.equals(proposal.status())
                    ? "incident.proposal_stale" : "incident.proposal_not_pending";
            throw new BizException(code, proposalId);
        }
        return approvalService.approve(proposal.agentActionId(), approver, confirmation, locale);
    }

    /** 驳回修复提案：提案 PENDING→REJECTED + 事故 AWAITING_APPROVAL→NEEDS_HUMAN + SYSTEM 消息。 */
    public ApprovalService.ApprovalResult rejectProposal(UUID incidentId, UUID proposalId, String approver) {
        IncidentProposal proposal = proposalRepository.findById(proposalId)
                .filter(p -> incidentId.equals(p.incidentId()))
                .orElseThrow(() -> new BizException("incident.proposal_not_found", proposalId));
        if (proposal.agentActionId() == null || !ProposalStatuses.PENDING.equals(proposal.status())) {
            throw new BizException("incident.proposal_not_pending", proposalId);
        }
        ApprovalService.ApprovalResult result = approvalService.reject(proposal.agentActionId(), approver);
        proposalRepository.casStatus(proposalId, ProposalStatuses.PENDING, ProposalStatuses.REJECTED);
        boolean stateOk = incidentRepo.casState(incidentId, IncidentStates.AWAITING_APPROVAL, IncidentStates.NEEDS_HUMAN);
        if (stateOk) {
            var msg = messageRepo.append(incidentId, MessageKinds.SYSTEM, "修复提案已驳回，转人工介入", null,
                    approver != null ? approver : "system");
            broadcastMessageAndIncident(incidentId, msg);
        }
        return result;
    }

    /**
     * 人工已介入前置校验（T021 硬要求）：处置动作提交前重新读取实例最新状态，若已偏离 actOrVerify 顶部
     * 观测的终态快照（说明人工已 kill/rerun 或其它进程已介入），本轮让位，不重复处置，落 SYSTEM 消息。
     */
    private boolean instanceStillNeedsRemediation(Incident inc, TaskInstance observed) {
        TaskInstance fresh = instanceRepository.findById(observed.getId()).orElse(null);
        if (fresh != null && observed.getState().equals(fresh.getState())) {
            return true;
        }
        var msg = messageRepo.append(inc.id(), MessageKinds.SYSTEM,
                "检测到实例状态已变化（可能人工已介入），本轮处置让位", null, "system");
        broadcastMessageAndIncident(inc.id(), msg);
        return false;
    }

    private void applyGateResult(Incident inc, RemediationPlanner.Decision decision, GateResult result) {
        incidentRepo.incrementAutoAction(inc.id());
        boolean ok = result.executed() && result.resultInstanceId() != null;
        String content = ok
                ? ("处置动作已执行：" + decision.reason())
                : ("处置动作未生效（" + result.outcome() + "）：" + result.message());
        String payload = toJson(Map.of("outcome", result.outcome().name(),
                "actionId", result.actionId() == null ? -1 : result.actionId()));
        var msg = messageRepo.append(inc.id(), MessageKinds.ACTION, content, payload, "ops-agent");
        broadcastMessageAndIncident(inc.id(), msg);
    }

    private void escalate(Incident inc, String reason) {
        boolean ok = incidentRepo.casState(inc.id(), IncidentStates.ACTING, IncidentStates.NEEDS_HUMAN);
        if (!ok) return;
        // 清收口口径提示位（对偶 markHandled/reverify 的预置）：本轮未能收口，提示位不得残留误标下一次收口。
        incidentRepo.clearCloseKindHint(inc.id());
        var msg = messageRepo.append(inc.id(), MessageKinds.SYSTEM, "转人工介入：" + reason, null, "system");
        broadcastMessageAndIncident(inc.id(), msg);
        briefingService.markDirty(inc.tenantId(), inc.projectId());
    }

    private void closeResolved(Incident inc, String closeKind) {
        boolean ok = incidentRepo.casClose(inc.id(), closeKind);
        if (!ok) return;
        String content = "HUMAN_ASSISTED".equals(closeKind) ? "事故已收口（人工介入后复验通过）" : "事故已自动收口（验证通过）";
        var msg = messageRepo.append(inc.id(), MessageKinds.SYSTEM, content, null, "system");
        broadcastMessageAndIncident(inc.id(), msg);
        briefingService.markDirty(inc.tenantId(), inc.projectId());
    }

    /** T026：SUCCESS 收口口径判定——markHandled/reverify 预置的提示位存在则收口 HUMAN_ASSISTED，否则 AUTO。 */
    private String resolveCloseKind(Incident inc) {
        return "HUMAN_ASSISTED".equals(inc.closeKind()) ? "HUMAN_ASSISTED" : "AUTO";
    }

    /**
     * T026：人工标记已处理（NEEDS_HUMAN 限定）——转 ACTING + 预置 HUMAN_ASSISTED 收口提示位 + 落 HUMAN_SAY
     * 备注 + 直接提交 incident_reverify（绕开 RemediationPlanner：CONFIG_CREDENTIAL/UPSTREAM_DATA 分型下
     * planner 恒 ESCALATE，若走通用决策会立即弹回 NEEDS_HUMAN，人工修复永远得不到复验机会）。
     * 后续终态判定交回 actOrVerify 通用循环（SUCCESS 收口读提示位；再失败→ escalate 清位，不重复生成提案）。
     */
    public void markHandled(UUID incidentId, String note, String actor) {
        Incident inc = incidentRepo.findById(incidentId).orElse(null);
        if (inc == null) {
            throw new BizException("incident.not_found", incidentId);
        }
        if (!IncidentStates.NEEDS_HUMAN.equals(inc.state())) {
            throw new BizException("incident.mark_handled_state_invalid");
        }
        boolean cas = incidentRepo.casStateWithCloseKindHint(incidentId, IncidentStates.NEEDS_HUMAN,
                IncidentStates.ACTING, "HUMAN_ASSISTED");
        if (!cas) {
            throw new BizException("incident.state_conflict", inc.state());
        }
        var msg = messageRepo.append(incidentId, MessageKinds.HUMAN_SAY,
                note != null && !note.isBlank() ? note : "已处理，触发复验", null, actorOrDefault(actor));
        broadcastMessageAndIncident(incidentId, msg);
        triggerReverify(incidentId, actor);
    }

    /** T026：显式触发复验（NEEDS_HUMAN 限定），同 markHandled 的复验触发段但不落 HUMAN_SAY 备注。 */
    public void reverify(UUID incidentId, String actor) {
        Incident inc = incidentRepo.findById(incidentId).orElse(null);
        if (inc == null) {
            throw new BizException("incident.not_found", incidentId);
        }
        if (!IncidentStates.NEEDS_HUMAN.equals(inc.state())) {
            throw new BizException("incident.state_conflict", inc.state());
        }
        boolean cas = incidentRepo.casStateWithCloseKindHint(incidentId, IncidentStates.NEEDS_HUMAN,
                IncidentStates.ACTING, "HUMAN_ASSISTED");
        if (!cas) {
            throw new BizException("incident.state_conflict", inc.state());
        }
        triggerReverify(incidentId, actor);
    }

    private void triggerReverify(UUID incidentId, String actor) {
        Incident inc = incidentRepo.findById(incidentId).orElse(null);
        if (inc == null) return;
        TaskInstance latest = instanceRepository.findById(inc.latestInstanceId()).orElse(null);
        if (latest == null) {
            escalate(inc, "最新失败实例已不存在，转人工介入");
            return;
        }
        ActionRequest req = ActionRequest.builder()
                .toolName("incident_reverify").actionType("INCIDENT_REVERIFY")
                .targetType("TASK_INSTANCE").targetId(inc.latestInstanceId().toString())
                .actor(actorOrDefault(actor)).actorSource("UI")
                .summary("人工触发复验（事故 " + incidentId + "）")
                .build();
        GateResult result = gatedActionService.submit(req);
        boolean ok = result.executed() && result.resultInstanceId() != null;
        String content = ok ? "复验已触发" : ("复验未生效（" + result.outcome() + "）：" + result.message());
        String payload = toJson(Map.of("outcome", result.outcome().name(),
                "actionId", result.actionId() == null ? -1 : result.actionId()));
        var msg = messageRepo.append(incidentId, MessageKinds.ACTION, content, payload, actorOrDefault(actor));
        broadcastMessageAndIncident(incidentId, msg);
    }

    /** T026：人工直接收口——任意非终态 → RESOLVED(MANUAL)，reason 必填（casClose 已支持任意非终态源状态）。 */
    public void closeManual(UUID incidentId, String reason, String actor) {
        if (reason == null || reason.isBlank()) {
            throw new BizException("incident.close_reason_required");
        }
        boolean ok = incidentRepo.casClose(incidentId, "MANUAL");
        if (!ok) {
            throw new BizException("incident.closed");
        }
        var msg = messageRepo.append(incidentId, MessageKinds.SYSTEM, "人工直接收口：" + reason, null, actorOrDefault(actor));
        broadcastMessageAndIncident(incidentId, msg);
        incidentRepo.findById(incidentId).ifPresent(i -> briefingService.markDirty(i.tenantId(), i.projectId()));
    }

    private String actorOrDefault(String actor) {
        return actor != null && !actor.isBlank() ? actor : "user";
    }

    private Integer jsonInt(String json, String key) {
        try {
            var node = objectMapper.readTree(json);
            return node.hasNonNull(key) ? node.get(key).asInt() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void degradeUnavailable(UUID incidentId, String reason) {
        boolean ok = incidentRepo.casState(incidentId, IncidentStates.ANALYZING, IncidentStates.DIAG_UNAVAILABLE);
        if (!ok) return;
        var msg = messageRepo.append(incidentId, MessageKinds.SYSTEM, "诊断不可用：" + reason, null, "system");
        broadcastMessageAndIncident(incidentId, msg);
    }

    private void broadcastIncident(UUID incidentId) {
        incidentRepo.findById(incidentId).ifPresent(inc ->
                publisher.publish(inc.projectId(), new IncidentEvent.IncidentChanged(inc)));
    }

    private void broadcastMessageAndIncident(UUID incidentId, com.dataweave.master.domain.incident.IncidentMessage msg) {
        incidentRepo.findById(incidentId).ifPresent(inc -> {
            publisher.publish(inc.projectId(), new IncidentEvent.MessageAppended(incidentId, msg));
            publisher.publish(inc.projectId(), new IncidentEvent.IncidentChanged(inc));
        });
    }

    private void thinking(long projectId, UUID incidentId, String phase, String label) {
        publisher.publish(projectId, new IncidentEvent.Thinking(incidentId, phase, label));
    }

    private void chip(long projectId, UUID incidentId, String chipId, String label, String status) {
        publisher.publish(projectId, new IncidentEvent.Chip(incidentId, chipId, label, status));
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }

    private String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    /** 独立令牌桶（与 053 血缘富化限频分离，research R3）。单 JVM 内存态，同 LineageAgentEnricher 已知限制。 */
    private static final class TokenBucket {
        private final double ratePerNano;
        private final long capacity;
        private double tokens;
        private long lastRefillNano;

        TokenBucket(long perMinute) {
            this.capacity = perMinute;
            this.ratePerNano = (double) perMinute / TimeUnit.MINUTES.toNanos(1);
            this.tokens = perMinute;
            this.lastRefillNano = System.nanoTime();
        }

        synchronized boolean tryAcquire() {
            long now = System.nanoTime();
            double added = (now - lastRefillNano) * ratePerNano;
            tokens = Math.min(capacity, tokens + added);
            lastRefillNano = now;
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }
    }
}
