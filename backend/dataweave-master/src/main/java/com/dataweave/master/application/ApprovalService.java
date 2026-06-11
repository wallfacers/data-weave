package com.dataweave.master.application;

import com.dataweave.master.domain.AgentAction;
import com.dataweave.master.domain.AgentActionRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 审批单生命周期：批准 / 拒绝 / 超时过期。审批单即 {@link AgentAction}（approval_status 流转），不另设表。
 *
 * <p>批准后平台侧按票据原始参数执行（不经 LLM，design D4）。L3 批准需二次确认（回输目标对象名）。
 */
@Service
public class ApprovalService {

    private final AgentActionRepository actionRepository;
    private final PlatformActionExecutor executor;

    public ApprovalService(AgentActionRepository actionRepository, PlatformActionExecutor executor) {
        this.actionRepository = actionRepository;
        this.executor = executor;
    }

    public List<AgentAction> pending() {
        return actionRepository.findByApprovalStatusOrderByIdAsc("PENDING");
    }

    public Optional<AgentAction> get(Long id) {
        return actionRepository.findById(id);
    }

    /**
     * 批准并执行。L3 需 confirmation 等于目标对象名（targetId）。
     *
     * @param id           审批单 id
     * @param approver     审批人
     * @param confirmation L3 二次确认（回输的对象名）；非 L3 可为 null
     */
    public ApprovalResult approve(Long id, String approver, String confirmation) {
        AgentAction action = actionRepository.findById(id).orElse(null);
        if (action == null) {
            return ApprovalResult.fail("未找到审批单 #" + id);
        }
        // 超时检查（懒过期）
        if (isExpired(action)) {
            markExpired(action);
            return ApprovalResult.fail("审批单 #" + id + " 已超时过期，无法批准。");
        }
        if (!"PENDING".equals(action.getApprovalStatus())) {
            return ApprovalResult.fail("审批单 #" + id + " 状态为 " + action.getApprovalStatus() + "，不可批准。");
        }
        // L3 二次确认
        if ("L3".equals(action.getPolicyLevel())) {
            String expected = action.getTargetId();
            if (confirmation == null || expected == null || !confirmation.trim().equals(expected.trim())) {
                return ApprovalResult.fail("L3 不可逆操作需回输目标对象名「" + expected + "」二次确认。");
            }
        }

        LocalDateTime now = LocalDateTime.now();
        action.setApprovalStatus("APPROVED");
        action.setApprovedBy(approver);
        action.setApprovedAt(now);

        PlatformActionExecutor.ExecOutcome out = executor.execute(action);
        action.setExecutedAt(LocalDateTime.now());
        action.setResultJson(out.resultJson());
        action.setUpdatedAt(LocalDateTime.now());
        actionRepository.save(action);
        return ApprovalResult.ok(action, out.message(), out.resultInstanceId());
    }

    public ApprovalResult reject(Long id, String approver) {
        AgentAction action = actionRepository.findById(id).orElse(null);
        if (action == null) {
            return ApprovalResult.fail("未找到审批单 #" + id);
        }
        if (!"PENDING".equals(action.getApprovalStatus())) {
            return ApprovalResult.fail("审批单 #" + id + " 状态为 " + action.getApprovalStatus() + "，不可拒绝。");
        }
        action.setApprovalStatus("REJECTED");
        action.setApprovedBy(approver);
        action.setApprovedAt(LocalDateTime.now());
        action.setUpdatedAt(LocalDateTime.now());
        actionRepository.save(action);
        return ApprovalResult.ok(action, "审批单 #" + id + " 已拒绝。", null);
    }

    /** 把超时未处理的 PENDING 审批单置 EXPIRED。返回过期条数。供定时任务调用。 */
    public int expireStale() {
        int n = 0;
        for (AgentAction action : actionRepository.findByApprovalStatusOrderByIdAsc("PENDING")) {
            if (isExpired(action)) {
                markExpired(action);
                n++;
            }
        }
        return n;
    }

    private boolean isExpired(AgentAction action) {
        return action.getExpiresAt() != null && action.getExpiresAt().isBefore(LocalDateTime.now());
    }

    private void markExpired(AgentAction action) {
        action.setApprovalStatus("EXPIRED");
        action.setUpdatedAt(LocalDateTime.now());
        actionRepository.save(action);
    }

    /**
     * 审批操作结果。
     *
     * @param success          是否成功
     * @param message          反馈
     * @param action           审批单（成功时）
     * @param resultInstanceId 执行产生的实例 id（若有）
     */
    public record ApprovalResult(boolean success, String message, AgentAction action, java.util.UUID resultInstanceId) {
        static ApprovalResult ok(AgentAction a, String msg, java.util.UUID instanceId) {
            return new ApprovalResult(true, msg, a, instanceId);
        }

        static ApprovalResult fail(String msg) {
            return new ApprovalResult(false, msg, null, null);
        }
    }
}
