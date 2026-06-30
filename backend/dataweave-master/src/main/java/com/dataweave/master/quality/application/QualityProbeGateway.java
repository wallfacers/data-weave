package com.dataweave.master.quality.application;

/**
 * 质量探针同步执行接缝（022-data-quality）。
 *
 * <p>master application 层把编译好的度量 SQL 经本接口同步执行、读回标量 measured_value；
 * all-in-one 由 api 组合根 {@code InProcessQualityProbeGateway} 实现（注入 worker
 * {@code QualityProbeExecutor} + {@code DatasourceResolver}，进程内同步 executor.execute）。
 * distributed 实现留 TODO（v1 all-in-one 验证）。
 *
 * <p><b>为何不同步复用的 {@link com.dataweave.master.application.TaskExecutionGateway#dispatch}</b>：
 * 该 dispatch 是 void 异步（fire-and-forget，结果靠 reportFinished 回调），质量裁决需要同步拿标量
 * 比较期望 → 写 result → BLOCK 阻断下游。本接口只暴露同步 probe，不 hack 调度异步接缝。
 *
 * @see com.dataweave.worker.infrastructure.QualityProbeExecutor
 */
public interface QualityProbeGateway {

    /**
     * @param datasourceId 目标数据源（经 DatasourceResolver 解析连接）
     * @param measureSql   度量 SQL（单 SELECT，只读，由 QualityRuleCompiler 编译）
     * @param timeoutSec   查询超时秒数（0=不限）
     * @return 探针结果
     */
    ProbeOutcome probe(long datasourceId, String measureSql, int timeoutSec);

    /** 探针结果。 */
    record ProbeOutcome(String measuredValue, boolean skipped, String error) {
        public static ProbeOutcome measured(String value) {
            return new ProbeOutcome(value, false, null);
        }
        public static ProbeOutcome skipped(String reason) {
            return new ProbeOutcome(null, true, reason);
        }
        public static ProbeOutcome error(String message) {
            return new ProbeOutcome(null, false, message);
        }
    }
}
