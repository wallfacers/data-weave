package com.dataweave.worker.infrastructure;

import com.dataweave.master.infrastructure.IsolatedDriverLoader;
import com.dataweave.worker.domain.ExecutionContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.function.Consumer;

/**
 * 质量探针执行器（022-data-quality，type=QUALITY_PROBE）。
 *
 * <p>继承 {@link SqlTaskExecutor} —— 复用建连（{@link #openConnection}）/ 驱动隔离 / SKIPPED 判定
 * （{@link #isConnectionFailure}），仅覆写一处：把 {@link ResultSet} 首行首列读为标量
 * {@code measured_value} 写入 {@code ExecutionResult.stdout} 回传。
 * 由此实现「复用 worker SQL 执行语义，不另起查询引擎」（宪法 III/V，research D1）。
 *
 * <p>语义分离（FR-007/SC-005 红线）：
 * <ul>
 *   <li>probe 返回 SKIPPED（未绑库 / 连不上 / 无驱动）→ result {@code ERROR}（基础设施失败）
 *       → 不发信号、不阻断、不计入质量分。</li>
 *   <li>真读回度量值 → 由调用方 {@code QualityCheckRunner} 进行 PASS/FAIL 比较。</li>
 * </ul>
 *
 * <p>measured_value 经 {@code stdout} 承载（{@link com.dataweave.worker.domain.TaskExecutor.ExecutionResult}
 * record 零侵入，方案 A）。{@code message} 放诊断摘要（耗时 / 跳过原因）。
 *
 * <p>@Component 自动被 {@code InProcessTaskExecutionGateway} / {@code WorkerExecService} 的 {@code byType}
 * 映射收录（按 {@link #type()} 大写作键），无需额外注册。
 */
@Component
@ConditionalOnBean(IsolatedDriverLoader.class)
public class QualityProbeExecutor extends SqlTaskExecutor {

    public QualityProbeExecutor(IsolatedDriverLoader isolatedLoader) {
        super(isolatedLoader);
    }

    @Override
    public String type() {
        return "QUALITY_PROBE";
    }

    @Override
    protected ExecutionResult doExecute(ExecutionContext ctx, Consumer<String> onLine) {
        String content = ctx.content() == null ? "" : ctx.content().trim();
        ExecutionContext.DataSourceRef ds = ctx.datasource();

        if (content.isEmpty()) {
            return new ExecutionResult(false, -1, "", "", false, false,
                    "[PROBE] 执行内容为空");
        }

        // 环境缺失：未绑定可用数据源 → SKIPPED（基础设施失败，FR-007/SC-005）
        if (ds == null || ds.jdbcUrl() == null || ds.jdbcUrl().isBlank()) {
            emit(onLine, "质量探针：未配置可用数据源，已跳过");
            return ExecutionResult.skipped("已跳过：未配置可用数据源");
        }

        emit(onLine, "质量探针 连接数据源：" + ds.name() + "（" + ds.typeCode() + "） " + ds.jdbcUrl());
        long t0 = System.currentTimeMillis();

        try (Connection conn = openConnection(ds)) {
            emit(onLine, "连接成功，执行度量 SQL");
            try (PreparedStatement ps = conn.prepareStatement(content)) {
                // 设置查询超时（与 ctx.timeoutSeconds 对齐，0=不限）
                int timeoutSec = ctx.timeoutSeconds();
                if (timeoutSec > 0) {
                    ps.setQueryTimeout(timeoutSec);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    String measuredValue;
                    if (rs.next()) {
                        // 首行首列作为标量度量值（行数/空值率/滞后量/违规行数…）
                        measuredValue = rs.getString(1);
                    } else {
                        measuredValue = null;
                    }
                    long cost = System.currentTimeMillis() - t0;
                    emit(onLine, String.format("度量完成：measured=%s, 耗时 %dms",
                            measuredValue != null ? measuredValue : "(null)", cost));
                    // measured_value 经 stdout 承载（record 零侵入）；message 放摘要
                    return new ExecutionResult(true, 0,
                            measuredValue != null ? measuredValue : "",
                            "", false, false,
                            "PROBE 完成，耗时 " + cost + "ms");
                }
            }
        } catch (SQLException e) {
            if (isConnectionFailure(e)) {
                emit(onLine, "数据源连接失败，已跳过：" + e.getMessage());
                return ExecutionResult.skipped("已跳过：数据源连接失败（" + e.getMessage() + "）");
            }
            emit(onLine, "度量 SQL 执行失败：" + e.getMessage());
            return new ExecutionResult(false, -1, "", e.getMessage(), false, false,
                    "PROBE SQL 执行失败：" + e.getMessage());
        } catch (Exception e) {
            emit(onLine, "驱动隔离加载/执行失败，已跳过：" + e.getMessage());
            return ExecutionResult.skipped("已跳过：驱动隔离加载失败（" + e.getMessage() + "）");
        }
    }

    private String firstLine(String s) {
        int nl = s.indexOf('\n');
        return nl >= 0 ? s.substring(0, nl) : s;
    }

    private void emit(Consumer<String> onLine, String line) {
        if (onLine != null) {
            onLine.accept(line);
        }
    }
}
