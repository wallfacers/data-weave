package com.dataweave.master.quality.application;

import com.dataweave.master.quality.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 质量执行编排器——三入口统一执行（research D2）：编译 → probe → 比较 → 写 result → 裁决 → 阻断/信号。
 *
 * <p>三入口（{@link CheckTrigger}）：
 * <ol>
 *   <li>POST_TASK —— {@link TaskSucceededListener} 消费 {@link TaskSucceededEvent} 触发（D2.1）。</li>
 *   <li>SCHEDULED —— {@link QualityScheduleTrigger} 独立调度触发（D2.2）。</li>
 *   <li>ON_DEMAND —— REST 或 agent 触发（D2.3）。</li>
 * </ol>
 *
 * <p>执行流程：
 * <ol>
 *   <li>建 {@link QualityCheckRun}（RUNNING）+ 快照规则定义。</li>
 *   <li>逐条编译 → 经 {@link QualityProbeGateway} 同步 probe 读标量。</li>
 *   <li>probe SKIPPED → result {@code ERROR}（基础设施失败，不发信号/不阻断/不计分）。</li>
 *   <li>真度量值 → {@link QualityExpectation#evaluate} → {@code PASS/FAIL/WARN}。</li>
 *   <li>FAIL + BLOCK → {@link QualityGateService#block} 阻断下游（D3）。</li>
 *   <li>FAIL/BLOCK→WARN → {@link QualitySignalEmitter#emit} 发 QUALITY_FAILED（D4）。</li>
 *   <li>{@link CheckStatus#aggregate} 归约 run 整体状态，更新 run。</li>
 * </ol>
 */
@Service
public class QualityCheckRunner {

    private static final Logger log = LoggerFactory.getLogger(QualityCheckRunner.class);

    private final QualityCheckRunRepository runRepository;
    private final QualityCheckResultRepository resultRepository;
    private final QualityRuleCompiler compiler;
    private final QualityProbeGateway probeGateway;
    private final QualityGateService gateService;
    private final QualitySignalEmitter signalEmitter;
    private final ObjectMapper om;

    public QualityCheckRunner(QualityCheckRunRepository runRepository,
                              QualityCheckResultRepository resultRepository,
                              QualityRuleCompiler compiler,
                              QualityProbeGateway probeGateway,
                              QualityGateService gateService,
                              QualitySignalEmitter signalEmitter,
                              ObjectMapper om) {
        this.runRepository = runRepository;
        this.resultRepository = resultRepository;
        this.compiler = compiler;
        this.probeGateway = probeGateway;
        this.gateService = gateService;
        this.signalEmitter = signalEmitter;
        this.om = om;
    }

    /**
     * @param rules           参与断言规则列表
     * @param trigger         触发入口
     * @param taskInstanceId  POST_TASK 关联的 task 实例（其余入口 null）
     * @param tenantId        租户
     * @return run id（on-demand 供 frontend/caller 用）
     */
    public Long run(List<QualityRule> rules, String trigger, UUID taskInstanceId, Long tenantId) {
        if (rules == null || rules.isEmpty()) {
            return null;
        }

        // 0. 快照规则定义（research D11：进行中编辑不影响本 run）
        String ruleSnapshot = snapshotRules(rules);

        String datasetRef = rules.get(0).getDatasetRef();
        QualityCheckRun run = new QualityCheckRun();
        run.setTenantId(tenantId);
        run.setDatasetRef(datasetRef);
        run.setTrigger(trigger);
        run.setTaskInstanceId(taskInstanceId);
        run.setStatus("RUNNING");
        run.setRuleCount(rules.size());
        run.setRuleSnapshotJson(ruleSnapshot);
        run.setStartedAt(LocalDateTime.now());
        run.setCreatedAt(LocalDateTime.now());
        run.setDeleted(0);
        run.setVersion(0);
        run = runRepository.save(run);

        Instant startInstant = Instant.now();
        List<CheckStatus> statuses = new ArrayList<>();
        int failCount = 0;
        boolean anyBlocked = false;
        boolean anySampled = false;

        for (QualityRule rule : rules) {
            QualityCheckResult result = executeSingle(rule, run.getId(), tenantId);
            statuses.add(CheckStatus.valueOf(result.getStatus()));

            if ("FAIL".equals(result.getStatus())) {
                failCount++;
                // BLOCK 阻断下游（D3）
                if ("BLOCK".equalsIgnoreCase(rule.getAction()) && rule.getBoundTaskId() != null
                        && taskInstanceId != null) {
                    int blocked = gateService.block(taskInstanceId, rule.getId(), result.getId(),
                            rule.getBoundTaskId());
                    if (blocked > 0) anyBlocked = true;
                }
                // 发 QUALITY_FAILED（D4，幂等）
                signalEmitter.emit(rule, result);
                result.setSignalEmitted(1);
            }
            if ("WARN".equals(result.getStatus())) {
                // WARN 也发信号（但 action 语义可降级）
                signalEmitter.emit(rule, result);
                result.setSignalEmitted(1);
            }
            if (result.getSampled() != null && result.getSampled() == 1) {
                anySampled = true;
            }
            resultRepository.save(result);
        }

        // 整体归约
        CheckStatus aggregate = CheckStatus.aggregate(statuses);
        long durationMs = Duration.between(startInstant, Instant.now()).toMillis();

        run.setStatus(aggregate.name());
        run.setFailCount(failCount);
        run.setSampled(anySampled ? 1 : 0);
        run.setBlocked(anyBlocked ? 1 : 0);
        run.setFinishedAt(LocalDateTime.now());
        run.setDurationMs(durationMs);
        runRepository.save(run);

        log.info("[QualityCheck] runId={} trigger={} dataset={} status={} rules={} fail={} blocked={} duration={}ms",
                run.getId(), trigger, datasetRef, aggregate, rules.size(), failCount,
                anyBlocked ? 1 : 0, durationMs);
        return run.getId();
    }

    /** 单断言：编译 → probe → 比较。 */
    private QualityCheckResult executeSingle(QualityRule rule, Long runId, Long tenantId) {
        QualityCheckResult result = new QualityCheckResult();
        result.setTenantId(tenantId);
        result.setRunId(runId);
        result.setRuleId(rule.getId());
        result.setAssertionType(rule.getAssertionType());
        result.setSampled(0);
        result.setSignalEmitted(0);
        result.setCreatedAt(LocalDateTime.now());
        result.setDeleted(0);
        result.setVersion(0);

        try {
            AssertionType type = AssertionType.valueOf(rule.getAssertionType());
            String expectationJson = rule.getExpectationJson();
            if (expectationJson == null || expectationJson.isBlank()) {
                result.setStatus("ERROR");
                result.setMessage("expectation_json 为空");
                return result;
            }

            // 编译度量 SQL
            QualityRuleCompiler.CompiledRule compiled = compiler.compile(
                    type, expectationJson, rule.getDatasetRef(), rule.getSamplingJson());
            if (compiled.sampling().isSampled()) {
                result.setSampled(1);
            }

            // SCHEMA 特殊：走 JDBC DatabaseMetaData 对比（非数据 SQL，暂留 TODO）
            if (type == AssertionType.SCHEMA) {
                result.setStatus("ERROR");
                result.setMessage("SCHEMA 断言暂未实现（需 JDBC DatabaseMetaData 对比，v1 留 TODO）");
                result.setExpected(compiled.expectation().expectedDescription());
                return result;
            }

            // 经同步 probe 读标量
            int timeoutSec = 30; // 默认 30s，后续从 rule config 读取
            QualityProbeGateway.ProbeOutcome probe = probeGateway.probe(
                    rule.getDatasourceId() != null ? rule.getDatasourceId() : 0L,
                    compiled.measureSql(), timeoutSec);

            if (probe.skipped()) {
                // 基础设施失败 → ERROR（不发信号/不阻断/不计分，SC-005）
                result.setStatus("ERROR");
                result.setMessage(probe.error());
                result.setExpected(compiled.expectation().expectedDescription());
                return result;
            }
            if (probe.error() != null) {
                result.setStatus("ERROR");
                result.setMessage(probe.error());
                result.setExpected(compiled.expectation().expectedDescription());
                return result;
            }

            // 真度量值 → 与期望比较
            QualityExpectation.QualityVerdict verdict = compiled.expectation().evaluate(probe.measuredValue());
            result.setMeasuredValue(verdict.measured());
            result.setExpected(verdict.expected());

            if (verdict.pass()) {
                result.setStatus("PASS");
                result.setMessage(null);
            } else {
                // 按 severity 区分 FAIL/WARN（CRITICAL/WARNING → FAIL；INFO → WARN）
                String sev = rule.getSeverity();
                if ("INFO".equalsIgnoreCase(sev)) {
                    result.setStatus("WARN");
                } else {
                    result.setStatus("FAIL");
                }
                result.setMessage(verdict.reason());
            }
        } catch (Exception e) {
            log.error("[QualityCheck] ruleId={} 执行异常: {}", rule.getId(), e.getMessage(), e);
            result.setStatus("ERROR");
            result.setMessage("执行异常：" + e.getMessage());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private String snapshotRules(List<QualityRule> rules) {
        try {
            List<Map<String, Object>> snap = new ArrayList<>();
            for (QualityRule r : rules) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", r.getId());
                m.put("assertionType", r.getAssertionType());
                m.put("expectationJson", r.getExpectationJson());
                m.put("action", r.getAction());
                m.put("severity", r.getSeverity());
                m.put("samplingJson", r.getSamplingJson());
                m.put("datasetRef", r.getDatasetRef());
                snap.add(m);
            }
            return om.writeValueAsString(snap);
        } catch (Exception e) {
            return "[]";
        }
    }
}
