package com.dataweave.worker.infrastructure;

import com.dataweave.master.domain.DriverJar;
import com.dataweave.master.domain.lineage.StatementMetric;
import com.dataweave.master.infrastructure.IsolatedDriverLoader;
import com.dataweave.worker.domain.AbstractTaskExecutor;
import com.dataweave.worker.domain.ExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Consumer;

/**
 * SQL 任务执行器（task-run-decouple，方案 A）。
 *
 * <p>按任务绑定的业务数据源连接执行 SQL，逐行回调诊断日志（连接、开始、每条语句影响/返回行数摘要、耗时）。
 * 查询类语句（{@code SELECT} / {@code SHOW TABLES} 等）渲染结果集到日志（表头+数据行，行数/单元格上限截断，FR-011a）。
 *
 * <p>**环境缺失 → SKIPPED（FR-008 / contracts C3）**：未绑定数据源 / 数据源不可用 / 无 JDBC 驱动 /
 * 连接失败 / 隔离加载失败时，返回可辨识的 SKIPPED（不再伪装成功），绝不抛错中断调度——保住
 * all-in-one / H2 克隆即跑、CI 零外部依赖底线（无数据源环境 SQL 表现为 SKIPPED，不阻塞下游）。
 *
 * <p>datasource-driver-isolation：数据源绑定上传 jar（driverJarId/storageKey/driverClass 齐全）时，
 * 走隔离 ClassLoader 加载（{@link IsolatedDriverLoader#connect}，绕过 DriverManager 的 ClassLoader 校验）；
 * 否则维持内置默认驱动经 {@link DriverManager}。
 */
@Component
public class SqlTaskExecutor extends AbstractTaskExecutor {

    /** 结果集渲染最大行数（contracts C7.2）。 */
    static final int MAX_RESULT_ROWS = 200;
    /** 结果集单元格最大字符数，超出截断。 */
    private static final int MAX_CELL_LENGTH = 100;

    private final IsolatedDriverLoader isolatedLoader;

    @Autowired
    public SqlTaskExecutor(@Autowired(required = false) IsolatedDriverLoader isolatedLoader) {
        this.isolatedLoader = isolatedLoader;
    }

    @Override
    public String type() {
        return "SQL";
    }

    @Override
    protected ExecutionResult doExecute(ExecutionContext ctx, Consumer<String> onLine) {
        String content = ctx.content() == null ? "" : ctx.content().trim();
        ExecutionContext.DataSourceRef ds = ctx.datasource();
        StringBuilder captured = new StringBuilder();
        // 复合 emit：同时写入 captured stdout 和 onLine 回调（确保 distributed worker 日志不丢）
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

        // 环境缺失：未绑定可用数据源 → SKIPPED（不伪装成功、不阻塞下游，FR-008 / contracts C3）
        if (ds == null || ds.jdbcUrl() == null || ds.jdbcUrl().isBlank()) {
            emitLine.accept("未配置可用数据源，已跳过（绑定数据源后走真实连库执行）");
            emitLine.accept("脚本预览：" + firstLine(content));
            return ExecutionResult.skippedWithStdout(captured.toString(), "已跳过：未配置可用数据源");
        }

        emitLine.accept("连接数据源：" + ds.name() + "（" + ds.typeCode() + "） " + maskJdbcUrl(ds.jdbcUrl()));
        long t0 = System.currentTimeMillis();
        try (Connection conn = openConnection(ds)) {
            emitLine.accept("连接成功，开始执行");
            List<String> statements = splitStatements(content);
            List<StatementMetric> metrics = new ArrayList<>();   // feature 025: per-statement affected-rows（喂 recordSynced）
            int idx = 0;
            for (String sql : statements) {
                idx++;
                long s0 = System.currentTimeMillis();
                try (Statement st = conn.createStatement()) {
                    boolean hasResultSet = st.execute(sql);
                    long cost = System.currentTimeMillis() - s0;
                    if (hasResultSet) {
                        try (ResultSet rs = st.getResultSet()) {
                            renderResultSet(rs, idx, statements.size(), cost, emitLine);
                        }
                    } else {
                        int updateCount = st.getUpdateCount();
                        emitLine.accept(String.format("语句 %d/%d 执行完成：影响 %d 行，耗时 %dms",
                                idx, statements.size(), updateCount, cost));
                        // feature 025: 收集写语句 affected-rows（>=0；SELECT/DDL hasResultSet 不收）
                        if (updateCount >= 0) {
                            metrics.add(new StatementMetric(sql, updateCount));
                        }
                    }
                }
            }
            long total = System.currentTimeMillis() - t0;
            emitLine.accept("全部语句执行完成，共 " + statements.size() + " 条，总耗时 " + total + "ms");
            return ExecutionResult.successWithMetrics(0, captured.toString(), "", "执行完成", metrics);
        } catch (SQLException e) {
            // 连接期失败（无驱动 / 无法建连）→ SKIPPED（环境缺失）；语句级 SQL 错误 → 如实置失败。
            if (isConnectionFailure(e)) {
                emitLine.accept("数据源连接失败，已跳过：" + e.getMessage());
                return ExecutionResult.skippedWithStdout(captured.toString(),
                        "已跳过：数据源连接失败（" + e.getMessage() + "）");
            }
            emitLine.accept("SQL 执行失败：" + e.getMessage());
            return new ExecutionResult(false, -1, captured.toString(), e.getMessage(), false, false, "SQL 执行失败");
        } catch (Exception e) {
            // 隔离加载失败（上传 jar 损坏 / 驱动类缺失等 RuntimeException）→ SKIPPED（环境缺失），不中断调度闭环
            emitLine.accept("驱动隔离加载/执行失败，已跳过：" + e.getMessage());
            return ExecutionResult.skippedWithStdout(captured.toString(),
                    "已跳过：驱动隔离加载失败（" + e.getMessage() + "）");
        }
    }

    /** 建连：绑定了上传 jar（元数据齐全）→ 隔离加载 driver.connect；否则内置 DriverManager。
     *  <p>可见性提为 protected 以便 {@code QualityProbeExecutor} 复用建连/驱动隔离不变量（022-data-quality）。 */
    protected Connection openConnection(ExecutionContext.DataSourceRef ds) throws Exception {
        if (ds.driverJarId() != null && ds.storageKey() != null && ds.driverClass() != null) {
            DriverJar jar = new DriverJar();
            jar.setStorageKey(ds.storageKey());
            jar.setDriverClass(ds.driverClass());
            Properties props = new Properties();
            if (ds.username() != null) props.setProperty("user", ds.username());
            if (ds.password() != null) props.setProperty("password", ds.password());
            if (isolatedLoader != null) {
                return isolatedLoader.connect(jar, ds.jdbcUrl(), props);
            }
            // 028: distributed worker 无 IsolatedDriverLoader bean，回退 DriverManager
            return DriverManager.getConnection(ds.jdbcUrl(), ds.username(), ds.password());
        }
        return DriverManager.getConnection(ds.jdbcUrl(), ds.username(), ds.password());
    }

    /** 连接期失败判定：无驱动 / 无法建连 → 方案 A 回退（非语句级 SQL 错误）。
     *  <p>可见性提为 protected 以便 {@code QualityProbeExecutor} 复用（022-data-quality）。 */
    protected boolean isConnectionFailure(SQLException e) {
        String state = e.getSQLState();
        // SQLState 08xxx = connection exception；DriverManager 无驱动抛 "No suitable driver"（state 多为 08001/null）。
        if (state != null && state.startsWith("08")) {
            return true;
        }
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        return msg.contains("no suitable driver") || msg.contains("connection refused")
                || msg.contains("communications link failure") || msg.contains("unknown host")
                || msg.contains("could not connect") || msg.contains("connect timed out");
    }

    /** 朴素分号切分（本期足够；不处理字符串字面量内分号——后续可换 SQL 解析器）。
     *  <p>可见性提为 protected 以便 {@code HiveTaskExecutor} 复用 HQL 语句切分。 */
    protected List<String> splitStatements(String content) {
        List<String> out = new ArrayList<>();
        for (String part : content.split(";")) {
            String s = part.trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        if (out.isEmpty()) {
            out.add(content);
        }
        return out;
    }

    private String firstLine(String s) {
        int nl = s.indexOf('\n');
        return nl >= 0 ? s.substring(0, nl) : s;
    }

    /**
     * 渲染 ResultSet 到日志（表头 + 数据行），带行数上限与单元格截断（contracts C7.2）。
     * <p>公开静态方法，供 {@code HiveTaskExecutor} 等复用结果集渲染语义。
     *
     * @param rs         已执行的 ResultSet（{@code st.getResultSet()}，hasResultSet==true）
     * @param idx        语句序号（1-based，仅用于日志标注）
     * @param totalStmts 总语句数
     * @param costMs     本条语句耗时 ms
     * @param emitLine   日志输出回调
     */
    public static void renderResultSet(ResultSet rs, int idx, int totalStmts, long costMs,
                                        Consumer<String> emitLine) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int colCount = meta.getColumnCount();

        // 表头
        StringBuilder header = new StringBuilder();
        for (int c = 1; c <= colCount; c++) {
            if (c > 1) header.append(" | ");
            header.append(meta.getColumnLabel(c));
        }
        emitLine.accept(header.toString());
        emitLine.accept("-".repeat(Math.min(header.length(), 80)));

        // 数据行
        int rowCount = 0;
        while (rs.next() && rowCount < MAX_RESULT_ROWS) {
            StringBuilder row = new StringBuilder();
            for (int c = 1; c <= colCount; c++) {
                if (c > 1) row.append(" | ");
                String val = rs.getString(c);
                if (val != null) {
                    if (val.length() > MAX_CELL_LENGTH) {
                        val = val.substring(0, MAX_CELL_LENGTH) + "...";
                    }
                    row.append(val);
                } else {
                    row.append("NULL");
                }
            }
            emitLine.accept(row.toString());
            rowCount++;
        }

        // 截断标注
        if (rs.next()) {
            emitLine.accept("已截断，仅显示前 " + MAX_RESULT_ROWS + " 行");
        }

        emitLine.accept(String.format("语句 %d/%d 执行完成：返回 %d 行结果集，耗时 %dms",
                idx, totalStmts, rowCount, costMs));
    }

    /** 脱敏 JDBC URL 中的 password 参数（FR-017 / contracts C7.4）。 */
    static String maskJdbcUrl(String jdbcUrl) {
        if (jdbcUrl == null) return null;
        return jdbcUrl.replaceAll("([?&;]password=)[^&;]+", "$1***");
    }

}
