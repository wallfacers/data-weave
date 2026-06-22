package com.dataweave.worker.infrastructure;

import com.dataweave.worker.domain.AbstractTaskExecutor;
import com.dataweave.worker.domain.ExecutionContext;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * SQL 任务执行器（task-run-decouple，方案 A）。
 *
 * <p>按任务绑定的业务数据源连接执行 SQL，逐行回调诊断日志（连接、开始、每条语句影响/返回行数摘要、耗时）。
 * **本期不打印结果集**（{@code SELECT} 的行数据）——结果集展示对齐 open-db-studio 留作后续变更。
 *
 * <p>**方案 A 回退**：未绑定数据源 / 数据源不可用 / 无 JDBC 驱动 / 连接失败时，回退「模拟成功 + 日志显式标注」，
 * 绝不抛错中断调度——保住 all-in-one / H2 克隆即跑、CI 零外部依赖底线。
 */
@Component
public class SqlTaskExecutor extends AbstractTaskExecutor {

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
        try (Connection conn = DriverManager.getConnection(ds.jdbcUrl(), ds.username(), ds.password())) {
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
            // 区分：连接期失败 → 方案 A 回退模拟；执行期失败 → 如实置失败。
            // 以「是否已连上」粗分：getConnection 抛错（连接失败）这里统一按连接失败回退；
            // 语句执行错误同样落 SQLException，但语义应失败——用 errorCode/SQLState 难精确区分，
            // 故按「连接是否建立」判断：连接失败的 message 通常含驱动/网络线索，这里保守地：
            // 若驱动缺失或无法连接（典型 message）回退，否则置失败。
            if (isConnectionFailure(e)) {
                emit(onLine, "数据源连接失败，模拟执行：" + e.getMessage());
                return new ExecutionResult(true, 0, "[simulated] 连接失败", "", false, false,
                        "模拟执行成功（连接失败回退）");
            }
            emit(onLine, "SQL 执行失败：" + e.getMessage());
            return new ExecutionResult(false, -1, "", e.getMessage(), false, false, "SQL 执行失败");
        }
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
