package com.dataweave.master.application;

import com.dataweave.master.domain.AgentAction;
import com.dataweave.master.domain.AgentActionRepository;
import com.dataweave.master.i18n.Messages;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 副作用操作统一闸门：分级裁决 → 落 agent_action → L0/L1 直执行、L2/L3 建审批单、L4 拒绝。
 *
 * <p>所有写操作（MCP 工具、CLI、applyFix）都经此入口，无绕过路径（policy-engine spec）。
 *
 * <p>闸门响应（拒绝/待审批 message）经 {@link Messages} 按传入的 locale 本地化；默认走
 * {@link Messages#DEFAULT_LOCALE}（中文）。REST 控制器应传 UI locale，MCP 工具传 agent locale。
 */
@Service
public class GatedActionService {

    private final PolicyEngine policyEngine;
    private final PlatformActionExecutor executor;
    private final AgentActionRepository actionRepository;
    private final Messages messages;
    private final long approvalTtlMinutes;

    public GatedActionService(PolicyEngine policyEngine,
                              PlatformActionExecutor executor,
                              AgentActionRepository actionRepository,
                              Messages messages,
                              @Value("${policy.approval-ttl-minutes:30}") long approvalTtlMinutes) {
        this.policyEngine = policyEngine;
        this.executor = executor;
        this.actionRepository = actionRepository;
        this.messages = messages;
        this.approvalTtlMinutes = approvalTtlMinutes;
    }

    /** 默认 locale（中文）提交。 */
    public GateResult submit(ActionRequest req) {
        return submit(req, Messages.DEFAULT_LOCALE);
    }

    /** 按指定 locale 本地化裁决理由与闸门响应。 */
    public GateResult submit(ActionRequest req, Locale locale) {
        PolicyDecision decision = policyEngine.decide(req, locale);
        LocalDateTime now = LocalDateTime.now();

        AgentAction action = new AgentAction();
        action.setStepId(req.stepId());
        action.setPolicyLevel(decision.level().name());
        action.setActionType(req.actionType());
        action.setTargetType(req.targetType());
        action.setTargetId(req.targetId());
        action.setCommand(req.command());
        action.setSummary(req.summary());
        action.setActor(req.actor());
        action.setActorSource(req.actorSource());
        action.setCreatedAt(now);
        action.setUpdatedAt(now);
        action.setIncidentId(req.incidentId()); // 043: incident 卡片发起的闸门动作反向回挂工单

        return switch (decision.outcome()) {
            case REJECTED -> {
                String reason = String.join("；", decision.reasons());
                action.setApprovalStatus("NONE");
                Map<String, Object> rej = new LinkedHashMap<>();
                rej.put("rejected", true);
                rej.put("level", decision.level().name());
                rej.put("reason", reason);
                action.setResultJson(Json.obj(rej));
                AgentAction saved = actionRepository.save(action);
                yield new GateResult(GateResult.Outcome.REJECTED, saved.getId(), decision.level().name(),
                        messages.get("gate.rejected", locale, decision.level(), reason),
                        req.summary(), false, null);
            }
            case PENDING_APPROVAL -> {
                action.setApprovalStatus("PENDING");
                action.setExpiresAt(now.plusMinutes(approvalTtlMinutes));
                AgentAction saved = actionRepository.save(action);
                String summary = req.summary() != null ? req.summary()
                        : (req.actionType() + (req.targetId() != null ? " " + req.targetId() : ""));
                yield new GateResult(GateResult.Outcome.PENDING_APPROVAL, saved.getId(), decision.level().name(),
                        messages.get("gate.pending", locale, decision.level(), saved.getId()),
                        summary, decision.requiresConfirmation(), null);
            }
            case EXECUTE -> {
                action.setApprovalStatus("NONE");
                AgentAction saved = actionRepository.save(action);
                PlatformActionExecutor.ExecOutcome out = executor.execute(saved, locale);
                saved.setExecutedAt(LocalDateTime.now());
                saved.setResultJson(out.resultJson());
                saved.setUpdatedAt(LocalDateTime.now());
                actionRepository.save(saved);
                yield new GateResult(GateResult.Outcome.EXECUTED, saved.getId(), decision.level().name(),
                        out.message(), req.summary(), false, out.resultInstanceId());
            }
        };
    }
}
