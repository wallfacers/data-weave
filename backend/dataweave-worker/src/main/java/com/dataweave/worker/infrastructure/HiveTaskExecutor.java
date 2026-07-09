package com.dataweave.worker.infrastructure;

import com.dataweave.master.domain.lineage.StatementMetric;
import com.dataweave.master.infrastructure.IsolatedDriverLoader;
import com.dataweave.worker.domain.ExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Hive 任务执行器（FR-003）。
 *
 * <p>经 HiveServer2 JDBC 建连（复用 {@link SqlTaskExecutor} 建连/驱动隔离/连接失败判定语义），
 * 不使用 beeline 二进制（research D2）。HQL 多语句按序 execute、SET 会话指令不当作影响行数、
 * 分区写入无 updateCount 如实汇报、查询类语句复用 {@link SqlTaskExecutor#renderResultSet} 渲染结果集。
 *
 * <p>环境缺失（未绑 HIVE 数据源 / jdbcUrl 空 / 连接失败 / 无驱动）→ SKIPPED，
 * 语句级错误 → 失败退出码忠实透传（contracts C3）。
 */
@Component
public class HiveTaskExecutor extends SqlTaskExecutor {

    @Autowired
    public HiveTaskExecutor(@Autowired(required = false) IsolatedDriverLoader isolatedLoader) {
        super(isolatedLoader);
    }

    @Override
    public String type() {
        return "HIVE";
    }

    @Override
    protected ExecutionResult doExecute(ExecutionContext ctx, Consumer<String> onLine) {
        String content = ctx.content() == null ? "" : ctx.content().trim();
        ExecutionContext.DataSourceRef ds = ctx.datasource();
        StringBuilder captured = new StringBuilder();
        Consumer<String> emitLine = line -> {
            captured.append(line).append('\n');
            if (onLine != null) {
                onLine.accept(line);
            }
        };

        if (content.isEmpty()) {
            emitLine.accept("执行内容为空");
            return new ExecutionResult(false, -1, captured.toString(), "", false, false, "执行内容为空");
        }

        // 环境缺失：未绑定可用 Hive 数据源 → SKIPPED（contracts C3）
        if (ds == null || ds.jdbcUrl() == null || ds.jdbcUrl().isBlank()) {
            emitLine.accept("未配置可用 Hive 数据源，已跳过（绑定 HIVE 数据源后走真实连库执行）");
            emitLine.accept("脚本预览：" + firstLine(content));
            return ExecutionResult.skippedWithStdout(captured.toString(), "已跳过：未配置可用 Hive 数据源");
        }

        emitLine.accept("连接数据源：" + ds.name() + "（" + ds.typeCode() + "） " + maskJdbcUrl(ds.jdbcUrl()));
        long t0 = System.currentTimeMillis();
        try (Connection conn = openConnection(ds)) {
            emitLine.accept("连接成功，开始执行");
            List<String> statements = splitStatements(content);
            List<StatementMetric> metrics = new ArrayList<>();
            int idx = 0;
            for (String sql : statements) {
                idx++;
                long s0 = System.currentTimeMillis();
                try (Statement st = conn.createStatement()) {
                    boolean hasResultSet = st.execute(sql);
                    long cost = System.currentTimeMillis() - s0;
                    if (hasResultSet) {
                        // 查询类语句：复用 SqlTaskExecutor 结果集渲染（contracts C7.2）
                        try (ResultSet rs = st.getResultSet()) {
                            renderResultSet(rs, idx, statements.size(), cost, emitLine);
                        }
                    } else if (isSetStatement(sql)) {
                        // SET 会话指令：不当影响行数，独立标注
                        emitLine.accept(String.format("语句 %d/%d SET 会话指令执行完成，耗时 %dms",
                                idx, statements.size(), cost));
                    } else {
                        int updateCount = st.getUpdateCount();
                        if (updateCount >= 0) {
                            emitLine.accept(String.format("语句 %d/%d 执行完成：影响 %d 行，耗时 %dms",
                                    idx, statements.size(), updateCount, cost));
                            metrics.add(new StatementMetric(sql, updateCount));
                        } else {
                            // 分区写入等无 updateCount 场景：如实汇报执行完成，不误报行数
                            emitLine.accept(String.format("语句 %d/%d 执行完成，耗时 %dms",
                                    idx, statements.size(), cost));
                        }
                    }
                }
            }
            long total = System.currentTimeMillis() - t0;
            emitLine.accept("全部语句执行完成，共 " + statements.size() + " 条，总耗时 " + total + "ms");
            return ExecutionResult.successWithMetrics(0, captured.toString(), "", "执行完成", metrics);
        } catch (SQLException e) {
            if (isConnectionFailure(e)) {
                emitLine.accept("Hive 数据源连接失败，已跳过：" + e.getMessage());
                return ExecutionResult.skippedWithStdout(captured.toString(),
                        "已跳过：Hive 数据源连接失败（" + e.getMessage() + "）");
            }
            emitLine.accept("HQL 执行失败：" + e.getMessage());
            return new ExecutionResult(false, -1, captured.toString(), e.getMessage(), false, false, "HQL 执行失败");
        } catch (Exception e) {
            emitLine.accept("Hive 驱动隔离加载/执行失败，已跳过：" + e.getMessage());
            return ExecutionResult.skippedWithStdout(captured.toString(),
                    "已跳过：Hive 驱动隔离加载失败（" + e.getMessage() + "）");
        }
    }

    /** SET 会话指令判定（不区分大小写）。Hive 中 SET k=v 用于设置会话变量，不应计为影响行数。 */
    static boolean isSetStatement(String sql) {
        if (sql == null) return false;
        String trimmed = sql.trim();
        // SET 开头（大小写不敏感），排除 SET 后无空格的情况（如 SETTING）
        return trimmed.length() >= 3
                && (trimmed.charAt(0) == 'S' || trimmed.charAt(0) == 's')
                && (trimmed.charAt(1) == 'E' || trimmed.charAt(1) == 'e')
                && (trimmed.charAt(2) == 'T' || trimmed.charAt(2) == 't')
                && (trimmed.length() == 3 || Character.isWhitespace(trimmed.charAt(3)));
    }

    private String firstLine(String s) {
        int nl = s.indexOf('\n');
        return nl >= 0 ? s.substring(0, nl) : s;
    }
}
