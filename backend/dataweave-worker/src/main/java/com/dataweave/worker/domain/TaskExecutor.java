package com.dataweave.worker.domain;

import com.dataweave.master.domain.lineage.StatementMetric;

import java.util.List;
import java.util.function.Consumer;

/**
 * 任务执行器抽象接口（design D9）。
 *
 * <p>与 {@link com.dataweave.worker.application.ControlledCommandExecutor} 独立：
 * 本接口用于调度任务（信任链为发布流程审查，不设白名单）；
 * ControlledCommandExecutor 仅服务 Agent {@code node_exec} 诊断命令（白名单防护）。
 * 两条路径不得混用。
 *
 * <p>实现类通过 {@link #type()} 声明支持的任务类型（SHELL / SQL / …），调度侧按类型分发。
 */
public interface TaskExecutor {

    /** 该执行器支持的任务类型，如 SQL / SHELL。 */
    String type();

    /**
     * 执行任务内容。
     *
     * @param ctx   执行上下文（内容、环境变量、超时）
     * @param onLine 逐行输出回调（stdout/stderr 每行调用一次，可为空行），用于实时管道；
     *               若为 {@code null} 则不回调
     * @return 执行结果
     */
    ExecutionResult execute(ExecutionContext ctx, Consumer<String> onLine);

    /**
     * 执行结果。
     *
     * <p>三态（FR-008/009）：
     * <ul>
     *   <li>成功：{@code success=true, skipped=false}</li>
     *   <li>失败：{@code success=false, skipped=false}（作业自身错误 / 资产缺失 / 真实超时）</li>
     *   <li>跳过：{@code skipped=true}（环境缺失：无数据源 / 无 SPARK_HOME / spark-submit 不可用），
     *       success=false，调度层按「非失败完成、不阻塞下游」处理，不新增状态机状态（FR-012）</li>
     * </ul>
     *
     * @param success   是否成功（exitCode==0 且未超时且未跳过）
     * @param exitCode  进程退出码（超时/启动失败为 -1；跳过约定为 0，靠 skipped 区分）
     * @param stdout    标准输出（可能截断）
     * @param stderr    标准错误（可能截断）
     * @param truncated 输出是否被截断
     * @param timedOut  是否超时终止
     * @param message   面向用户/审计的摘要（跳过时含可辨识「已跳过：&lt;原因&gt;」）
     * @param skipped   环境缺失而未真实执行；与 success 互斥（skipped=true 时 success 不得为伪装的 true）
     * @param statementMetrics per-statement 执行指标（SQL 执行器填 affected-rows；其他执行器空 list）；
     *                         feature 025 运行态采集，喂 {@code recordSynced}，非 SQL 路径恒空
     */
    record ExecutionResult(boolean success, int exitCode, String stdout, String stderr,
                           boolean truncated, boolean timedOut, String message, boolean skipped,
                           List<StatementMetric> statementMetrics) {

        /** 向后兼容：真实成功/失败路径的 7 参构造（skipped=false, statementMetrics=空）。现有构造点零改动。 */
        public ExecutionResult(boolean success, int exitCode, String stdout, String stderr,
                               boolean truncated, boolean timedOut, String message) {
            this(success, exitCode, stdout, stderr, truncated, timedOut, message, false, List.of());
        }

        /** 8 参（带 skipped, statementMetrics=空）：{@link #skipped(String)} 工厂等内部用。 */
        public ExecutionResult(boolean success, int exitCode, String stdout, String stderr,
                               boolean truncated, boolean timedOut, String message, boolean skipped) {
            this(success, exitCode, stdout, stderr, truncated, timedOut, message, skipped, List.of());
        }

        /** SQL 成功路径带 per-statement 指标（feature 025 运行态采集；非 SQL 执行器走空 list）。 */
        public static ExecutionResult successWithMetrics(int exitCode, String stdout, String stderr,
                                                         String message, List<StatementMetric> metrics) {
            return new ExecutionResult(true, exitCode, stdout, stderr, false, false, message, false,
                    metrics != null ? metrics : List.of());
        }

        /** 环境缺失跳过（FR-008）：{@code success=false, exitCode=0, skipped=true, message=reason}。 */
        public static ExecutionResult skipped(String reason) {
            return new ExecutionResult(false, 0, "", "", false, false, reason, true);
        }

        /** 环境缺失跳过（带 stdout 日志）：{@code success=false, exitCode=0, skipped=true}。 */
        public static ExecutionResult skippedWithStdout(String stdout, String reason) {
            return new ExecutionResult(false, 0, stdout, "", false, false, reason, true);
        }
    }
}
