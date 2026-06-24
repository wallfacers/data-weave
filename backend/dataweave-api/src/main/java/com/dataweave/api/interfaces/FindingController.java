package com.dataweave.api.interfaces;

import com.dataweave.api.infrastructure.ApiResponse;
import com.dataweave.api.infrastructure.Locales;
import com.dataweave.master.application.FindingActionService;
import com.dataweave.master.application.FindingService;
import com.dataweave.master.domain.Finding;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import java.util.List;

/**
 * 通用发现 REST 端点：举手台列表（{@code GET /api/findings}）、一键修复（{@code POST /api/findings/{id}/apply}）。
 *
 * <p>修复经 {@link FindingActionService} → 现有 {@link com.dataweave.master.application.GatedActionService} 闸门，
 * 返回 outcome（EXECUTED/PENDING_APPROVAL/REJECTED）供前端分流，绝无绕过闸门的执行路径。
 */
@RestController
@RequestMapping("/api/findings")
public class FindingController {

    private final FindingService findingService;
    private final FindingActionService findingActionService;

    public FindingController(FindingService findingService, FindingActionService findingActionService) {
        this.findingService = findingService;
        this.findingActionService = findingActionService;
    }

    /** 举手台列表：当前 OPEN/ANNOUNCED 的发现，id 降序。 */
    @GetMapping
    public ApiResponse<List<Finding>> active() {
        return ApiResponse.ok(findingService.active());
    }

    /** 一键修复：执行该发现选定的修复项（经闸门）。 */
    @PostMapping("/{id}/apply")
    public ApiResponse<FindingActionService.Result> apply(@PathVariable Long id,
                                                          @RequestBody(required = false) ApplyRequest body,
                                                          ServerWebExchange exchange) {
        String actionKey = body != null && body.actionKey() != null ? body.actionKey() : "RERUN";
        return ApiResponse.ok(findingActionService.apply(id, actionKey, "ui-user", "UI",
                Locales.uiLocale(exchange.getRequest().getHeaders())));
    }

    public record ApplyRequest(String actionKey) {
    }
}
