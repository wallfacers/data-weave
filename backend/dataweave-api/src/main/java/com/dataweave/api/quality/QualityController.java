package com.dataweave.api.quality;

import com.dataweave.api.infrastructure.TenantContext;
import com.dataweave.master.i18n.BizException;
import com.dataweave.master.quality.application.*;
import com.dataweave.master.quality.domain.*;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 质量中心 REST 端点（022-data-quality，contracts/quality-api.md）。
 *
 * <p>统一约定：WebFlux；响应 {@code 200 + {code:0, data, message}}；错误走
 * {@link BizException} + {@code GlobalExceptionHandler}。全部按 {@code tenantId} 隔离（FR-010）。
 * 断言写 + on-demand 触发在 agent 路径经 {@code PolicyEngine} 闸门（D5）；
 * UI CRUD 走普通鉴权（本 Controller）+ 审计。
 */
@RestController
@RequestMapping("/api/quality")
public class QualityController {

    private final QualityRuleService ruleService;
    private final QualityCheckService checkService;
    private final QualityCheckRunner runner;
    private final QualityScorecardService scorecardService;

    public QualityController(QualityRuleService ruleService, QualityCheckService checkService,
                              QualityCheckRunner runner, QualityScorecardService scorecardService) {
        this.ruleService = ruleService;
        this.checkService = checkService;
        this.runner = runner;
        this.scorecardService = scorecardService;
    }

    // ── 断言 rule ─────────────────────────────────

    @GetMapping("/rules")
    public Map<String, Object> listRules() {
        long tid = requireTenant();
        List<QualityRule> rules = ruleService.list(tid);
        return ok(rules);
    }

    @GetMapping("/rules/{id}")
    public Map<String, Object> getRule(@PathVariable Long id) {
        long tid = requireTenant();
        QualityRule rule = ruleService.get(id, tid)
                .orElseThrow(() -> new BizException("quality.rule_not_found"));
        return ok(rule);
    }

    @PostMapping("/rules")
    public Map<String, Object> createRule(@RequestBody QualityRule rule) {
        long tid = requireTenant();
        rule.setTenantId(tid);
        rule.setId(null);
        return ok(ruleService.create(rule));
    }

    @PatchMapping("/rules/{id}")
    public Map<String, Object> updateRule(@PathVariable Long id, @RequestBody QualityRule patch) {
        long tid = requireTenant();
        QualityRule existing = ruleService.get(id, tid)
                .orElseThrow(() -> new BizException("quality.rule_not_found"));
        return ok(ruleService.update(existing, patch));
    }

    @DeleteMapping("/rules/{id}")
    public Map<String, Object> deleteRule(@PathVariable Long id) {
        long tid = requireTenant();
        ruleService.delete(id, tid);
        return ok(Map.of("deleted", true));
    }

    // ── 执行 run ───────────────────────────────────

    /** 立即检查（on-demand，单断言）。 */
    @PostMapping("/rules/{id}/run")
    public Map<String, Object> runRule(@PathVariable Long id) {
        long tid = requireTenant();
        QualityRule rule = ruleService.get(id, tid)
                .orElseThrow(() -> new BizException("quality.rule_not_found"));
        Long runId = runner.run(List.of(rule), "ON_DEMAND", null, tid);
        return ok(Map.of("runId", runId));
    }

    /** 对数据集批量 on-demand 检查。 */
    @PostMapping("/datasets/{datasetRef}/run")
    public Map<String, Object> runDataset(@PathVariable String datasetRef) {
        long tid = requireTenant();
        List<QualityRule> rules = ruleService.findByDataset(tid, datasetRef);
        if (rules.isEmpty()) {
            throw new BizException("quality.rule_not_found");
        }
        Long runId = runner.run(rules, "ON_DEMAND", null, tid);
        return ok(Map.of("runId", runId, "ruleCount", rules.size()));
    }

    @GetMapping("/runs")
    public Map<String, Object> listRuns() {
        long tid = requireTenant();
        return ok(checkService.listRuns(tid));
    }

    @GetMapping("/runs/{id}")
    public Map<String, Object> getRun(@PathVariable Long id) {
        long tid = requireTenant();
        QualityCheckRun run = checkService.getRun(id, tid)
                .orElseThrow(() -> new BizException("quality.run_not_found"));
        return ok(run);
    }

    @GetMapping("/runs/{id}/results")
    public Map<String, Object> getRunResults(@PathVariable Long id) {
        long tid = requireTenant();
        // verify run exists
        checkService.getRun(id, tid)
                .orElseThrow(() -> new BizException("quality.run_not_found"));
        return ok(checkService.getResults(id, tid));
    }

    // ── 结果下钻 result ────────────────────────────

    @GetMapping("/results/{id}")
    public Map<String, Object> getResult(@PathVariable Long id) {
        long tid = requireTenant();
        QualityCheckResult result = checkService.getResult(id, tid)
                .orElseThrow(() -> new BizException("quality.result_not_found"));
        return ok(result);
    }

    /** 失败样本取证（受租户+权限控制，FR-016）。 */
    @GetMapping("/results/{id}/sample")
    public Map<String, Object> getResultSample(@PathVariable Long id) {
        long tid = requireTenant();
        QualityCheckResult result = checkService.getResult(id, tid)
                .orElseThrow(() -> new BizException("quality.result_not_found"));
        if (result.getFailedSampleRef() == null || result.getFailedSampleRef().isBlank()) {
            return ok(Map.of("sample", (Object) null));
        }
        // v1: 简单返回引用（生产需经权限校验 + MinIO 解引用）
        return ok(Map.of("sample", result.getFailedSampleRef()));
    }

    // ── 评分卡 scorecard ───────────────────────────

    @GetMapping("/scorecards")
    public Map<String, Object> listScorecards() {
        long tid = requireTenant();
        return ok(scorecardService.list(tid));
    }

    @GetMapping("/scorecards/{datasetRef}")
    public Map<String, Object> getScorecard(@PathVariable String datasetRef) {
        long tid = requireTenant();
        QualityScorecard card = scorecardService.getOrCompute(tid, datasetRef);
        if (card == null) {
            throw new BizException("quality.run_not_found");
        }
        return ok(card);
    }

    // ── helper ─────────────────────────────────────

    private long requireTenant() {
        Long tid = TenantContext.tenantId();
        if (tid == null || tid <= 0) {
            throw new BizException("quality.tenant_required");
        }
        return tid;
    }

    private static Map<String, Object> ok(Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 0);
        body.put("data", data);
        body.put("message", "OK");
        return body;
    }
}
