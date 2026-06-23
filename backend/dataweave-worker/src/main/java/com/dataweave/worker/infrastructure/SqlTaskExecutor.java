package com.dataweave.worker.infrastructure;

import com.dataweave.master.domain.DriverJar;
import com.dataweave.master.infrastructure.IsolatedDriverLoader;
import com.dataweave.worker.domain.AbstractTaskExecutor;
import com.dataweave.worker.domain.ExecutionContext;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
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
 * **本期不打印结果集**（{@code SELECT} 的行数据）——结果集展示对齐 open-db-studio 留作后续变更。
 *
 * <p>**方案 A 回退**：未绑定数据源 / 数据源不可用 / 无 JDBC 騼动 / 连接失败 / 隔离加载失败时，
 * 回退「模拟成功 + 日志显式标注」，绝不抛错中断调度——保住 all-in-one / H2 克隆即跑、CI 零外部依赖底线。
 *
 * <p>datasource-driver-isolation：数据源绑定上传 jar（driverJarId/storageKey/driverClass 齐全）时，
 * 走隔离 ClassLoader 加载（{@link IsolatedDriverLoader#connect}，绕过 DriverManager 的 ClassLoader 校验）；
 * 否则维持内置默认驱动经 {@link DriverManager}。
 */
@Component
public class SqlTaskExecutor extends AbstractTaskExecutor {

    private final IsolatedDriverLoader isolatedLoader;

    public SqlTaskExecutor(IsolatedDriverLoader isolatedLoader) {
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

        if (content.isEmpty()) {
            return new ExecutionResult(false, -1, "", "", false, false, "执行内容为空");
        }

        // 方案 A 回退①：未绑定可用数据源 → 模拟成功 + 标注。
        if (ds == null || ds.jdbcUrl() == null || ds.jdbcUrl().isBlank()) {
            emit(onLine, "未配置可用数据源，模拟执行（绑定数据源后走真实连库执行）");
            emit(onLine, "脚本预览：" + firstLine(content));
            return new ExecutionResult(true, 0, "[simulated] 未配置数据源", "", false, false, "模拟执行成功（无数据源）");
        }

        emit(onLine, "连接数据源：" + ds.name() + "（" + ds.typeCode() + "） " + ds.jdbcUrl());
        long t0 = System.currentTimeMillis();
        try (Connection conn = openConnection(ds)) {
            emit(onLine, "连接成功，开始执行");
            List<String> statements = splitStatements(content);
            int idx = 0;
            for (String sql : statements) {
                idx++;
                long s0 = System.currentTimeMillis();
                try (Statement st = conn.createStatement()) {
                    boolean hasResultSet = st.execute(sql);
                    long cost = System.currentTimeMillis() - s0;
                    if (hasResultSet) {
                        // 本期不打印结果集，仅汇报有返回（行数据展示留作后续）。
                        emit(onLine, String.format("语句 %d/%d 执行完成：返回结果集（本期不展示行数据），耗时 %dms",
                                idx, statements.size(), cost));
                    } else {
                        emit(onLine, String.format("语句 %d/%d 执行完成：影响 %d 行，耗时 %dms",
                                idx, statements.size(), st.getUpdateCount(), cost));
                    }
                }
            }
            long total = System.currentTimeMillis() - t0;
            emit(onLine, "全部语句执行完成，共 " + statements.size() + " 条，总耗时 " + total + "ms");
            return new ExecutionResult(true, 0, "", "", false, false, "执行完成");
        } catch (SQLException e) {
            // 连接期失败（无驱动 / 无法建连）→ 方案 A 回退模拟；语句级 SQL 错误 → 如实置失败。
            if (isConnectionFailure(e)) {
                emit(onLine, "数据源连接失败，模拟执行：" + e.getMessage());
                return new ExecutionResult(true, 0, "[simulated] 连接失败", "", false, false,
                        "模拟执行成功（连接失败回退）");
            }
            emit(onLine, "SQL 执行失败：" + e.getMessage());
            return new ExecutionResult(false, -1, "", e.getMessage(), false, false, "SQL 执行失败");
        } catch (Exception e) {
            // 隔离加载失败（上传 jar 损坏 / 驱动类缺失等 RuntimeException）→ 降级方案 A 模拟执行，不中断调度闭环
            emit(onLine, "驱动隔离加载/执行失败，模拟执行：" + e.getMessage());
            return new ExecutionResult(true, 0, "[simulated] 驱动加载失败", "", false, false,
                    "模拟执行成功（驱动加载失败回退）");
        }
    }

    /** 建连：绑定了上传 jar（元数据齐全）→ 隔离加载 driver.connect；否则内置 DriverManager。 */
    private Connection openConnection(ExecutionContext.DataSourceRef ds) throws Exception {
        if (ds.driverJarId() != null && ds.storageKey() != null && ds.driverClass() != null) {
            DriverJar jar = new DriverJar();
            jar.setStorageKey(ds.storageKey());
            jar.setDriverClass(ds.driverClass());
            Properties props = new Properties();
            if (ds.username() != null) props.setProperty("user", ds.username());
            if (ds.password() != null) props.setProperty("password", ds.password());
            return isolatedLoader.connect(jar, ds.jdbcUrl(), props);
        }
        return DriverManager.getConnection(ds.jdbcUrl(), ds.username(), ds.password());
    }

    /** 连接期失败判定：无驱动 / 无法建连 → 方案 A 回退（非语句级 SQL 错误）。 */
    private boolean isConnectionFailure(SQLException e) {
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

    /** 朴素分号切分（本期足够；不处理字符串字面量内分号——后续可换 SQL 解析器）。 */
    private List<String> splitStatements(String content) {
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

    private void emit(Consumer<String> onLine, String line) {
        if (onLine != null) {
            onLine.accept(line);
        }
    }
}
