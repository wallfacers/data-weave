package com.dataweave.api.interfaces;

import com.dataweave.master.application.ApprovalService;
import com.dataweave.master.application.ApprovalService.ApprovalResult;
import com.dataweave.master.domain.AgentAction;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 审批单 REST 端点：列待审、批准（平台侧执行）、拒绝。右舷审批卡片调用本端点。
 * 批准走 {@link ApprovalService}（L3 需二次确认对象名），执行不经 LLM。
 */
@RestController
@RequestMapping("/api/approvals")
public class ApprovalController {

    private final ApprovalService approvalService;

    public ApprovalController(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @GetMapping("/pending")
    public List<AgentAction> pending() {
        return approvalService.pending();
    }

    @PostMapping("/{id}/approve")
    public ApprovalResult approve(@PathVariable Long id, @RequestBody(required = false) ApproveRequest body) {
        String approver = body != null && body.approver() != null ? body.approver() : "ui-user";
        String confirmation = body != null ? body.confirmation() : null;
        return approvalService.approve(id, approver, confirmation);
    }

    @PostMapping("/{id}/reject")
    public ApprovalResult reject(@PathVariable Long id, @RequestBody(required = false) ApproveRequest body) {
        String approver = body != null && body.approver() != null ? body.approver() : "ui-user";
        return approvalService.reject(id, approver);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ApproveRequest(String approver, String confirmation) {
    }
}
