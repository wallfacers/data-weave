package com.dataweave.master.application;

import com.dataweave.master.domain.Finding;
import com.dataweave.master.i18n.Messages;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * 通用发现的一键修复编排：把 Finding 选定的修复项经现有 {@link GatedActionService} 闸门执行。
 *
 * <p>TASK_FAILURE 来源复用 {@link DiagnosisService#submitFix}（同一闸门、同一 agent_action 留痕，绝无绕过）。
 * 闸门直接执行（EXECUTED）则把对应 Finding 置 RESOLVED 收口；裁为 PENDING_APPROVAL/REJECTED 则保持原状，
 * outcome 原样回传供前端分流。
 */
@Service
public class FindingActionService {

    private final FindingService findingService;
    private final DiagnosisService diagnosisService;
    private final Messages messages;

    public FindingActionService(FindingService findingService,
                                DiagnosisService diagnosisService,
                                Messages messages) {
        this.findingService = findingService;
        this.diagnosisService = diagnosisService;
        this.messages = messages;
    }

    /** UI 默认入口。 */
    public Result apply(Long findingId, String actionKey) {
        return apply(findingId, actionKey, "ui-user", "UI", Messages.DEFAULT_LOCALE);
    }

    public Result apply(Long findingId, String actionKey, String actor, String actorSource, Locale locale) {
        Optional<Finding> opt = findingService.get(findingId);
        if (opt.isEmpty()) {
            return new Result("REJECTED", false, messages.get("finding.not_found", locale, findingId), null);
        }
        Finding f = opt.get();

        if ("TASK_FAILURE".equals(f.getSource()) && f.getTaskDiagnosisId() != null) {
            GateResult gr = diagnosisService.submitFix(f.getTaskDiagnosisId(), actionKey, actor, actorSource, locale);
            if (gr.executed()) {
                findingService.resolve(findingId);
            }
            return new Result(gr.outcome().name(), gr.executed(), gr.message(), gr.resultInstanceId());
        }

        // 其余来源（数据质量/SLA…）的修复执行器后续接入；当前无可执行通道。
        return new Result("REJECTED", false, messages.get("finding.action_unsupported", locale, f.getSource()), null);
    }

    /**
     * 修复结果。
     *
     * @param outcome       EXECUTED / PENDING_APPROVAL / REJECTED（前端据此分流，不能只看 success）
     * @param executed      是否已直接执行
     * @param message       面向用户的反馈
     * @param newInstanceId 若产生重跑实例，其 id
     */
    public record Result(String outcome, boolean executed, String message, UUID newInstanceId) {
    }
}
