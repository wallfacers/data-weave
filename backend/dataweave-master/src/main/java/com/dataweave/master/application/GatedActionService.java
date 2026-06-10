package com.dataweave.master.application;

import com.dataweave.master.domain.AgentAction;
import com.dataweave.master.domain.AgentActionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 副作用操作统一闸门：分级裁决 → 落 agent_action → L0/L1 直执行、L2/L3 建审批单、L4 拒绝。
 *
 * <p>所有写操作（MCP 工具、CLI、applyFix）都经此入口，无绕过路径（policy-engine spec）。
 */
@Service
public class GatedActionService {

    private final PolicyEngine policyEngine;
    private final PlatformActionExecutor executor;
    private final AgentActionRepository actionRepository;
    private final long approvalTtlMinutes;

    public GatedActionService(PolicyEngine policyEngine,
                              PlatformActionExecutor executor,
                              AgentActionRepository actionRepository,
                              @Value("${policy.approval-ttl-minutes:30}") long approvalTtlMinutes) {
        this.policyEngine = policyEngine;
        this.executor = executor;
        this.actionRepository = actionRepository;
        this.approvalTtlMinutes = approvalTtlMinutes;
    }

    public GateResult submit(ActionRequest req) {
        PolicyDecision decision = policyEngine.decide(req);
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
                        "操作被策略禁止（" + decision.level() + "）：" + reason, req.summary(), false, null);
            }
            case PENDING_APPROVAL -> {
                action.setApprovalStatus("PENDING");
                action.setExpiresAt(now.plusMinutes(approvalTtlMinutes));
                AgentAction saved = actionRepository.save(action);
                String summary = req.summary() != null ? req.summary()
                        : (req.actionType() + (req.targetId() != null ? " " + req.targetId() : ""));
                yield new GateResult(GateResult.Outcome.PENDING_APPROVAL, saved.getId(), decision.level().name(),
                        "该操作为 " + decision.level() + "，已创建审批单 #" + saved.getId() + "，待人工批准。",
                        summary, decision.requiresConfirmation(), null);
            }
            case EXECUTE -> {
                action.setApprovalStatus("NONE");
                AgentAction saved = actionRepository.save(action);
                PlatformActionExecutor.ExecOutcome out = executor.execute(saved);
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
