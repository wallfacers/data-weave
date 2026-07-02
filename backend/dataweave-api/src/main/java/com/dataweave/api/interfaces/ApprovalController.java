package com.dataweave.api.interfaces;

import com.dataweave.api.infrastructure.ApiResponse;
import com.dataweave.api.infrastructure.Locales;
import com.dataweave.api.infrastructure.ProjectAuthz;
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
import org.springframework.web.server.ServerWebExchange;

import java.util.List;

/**
 * 审批单 REST 端点：列待审、批准（平台侧执行）、拒绝。右舷审批卡片调用本端点。
 * 批准走 {@link ApprovalService}（L3 需二次确认对象名），执行不经 LLM。
 */
@RestController
@RequestMapping("/api/approvals")
public class ApprovalController {

    private final ApprovalService approvalService;
    private final ProjectAuthz projectAuthz;

    public ApprovalController(ApprovalService approvalService, ProjectAuthz projectAuthz) {
        this.approvalService = approvalService;
        this.projectAuthz = projectAuthz;
    }

    @GetMapping("/pending")
    public ApiResponse<List<AgentAction>> pending() {
        return ApiResponse.ok(approvalService.pending());
    }

    @PostMapping("/{id}/approve")
    public ApiResponse<ApprovalResult> approve(@PathVariable Long id,
                                               @RequestBody(required = false) ApproveRequest body,
                                               ServerWebExchange exchange) {
        // 036-D2：审批 = OWNER only（project:manage，FR-042）。agent_action 无 project_id 列
        // （补列属 C 路 schema 独占面），暂按当前请求项目（X-Project-Id）授权——接缝已记入清单。
        projectAuthz.requireCurrent("project:manage");
        String approver = body != null && body.approver() != null ? body.approver() : "ui-user";
        String confirmation = body != null ? body.confirmation() : null;
        return ApiResponse.ok(approvalService.approve(id, approver, confirmation,
                Locales.uiLocale(exchange.getRequest().getHeaders())));
    }

    @PostMapping("/{id}/reject")
    public ApiResponse<ApprovalResult> reject(@PathVariable Long id, @RequestBody(required = false) ApproveRequest body) {
        projectAuthz.requireCurrent("project:manage"); // 036-D2：审批 = OWNER only（FR-042）
        String approver = body != null && body.approver() != null ? body.approver() : "ui-user";
        return ApiResponse.ok(approvalService.reject(id, approver));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ApproveRequest(String approver, String confirmation) {
    }
}
