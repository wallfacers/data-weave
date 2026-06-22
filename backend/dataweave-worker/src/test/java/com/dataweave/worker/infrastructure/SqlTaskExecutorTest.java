package com.dataweave.worker.infrastructure;

import com.dataweave.worker.domain.ExecutionContext;
import com.dataweave.worker.domain.ExecutionContext.DataSourceRef;
import com.dataweave.worker.domain.TaskExecutor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SqlTaskExecutor 方案 A：无数据源/连接失败回退「模拟成功 + 标注」，绝不抛错中断调度；
 * 配可用数据源（H2 内存）时真实执行、不打印结果集。
 */
class SqlTaskExecutorTest {

    private final SqlTaskExecutor executor = new SqlTaskExecutor();

    private ExecutionContext sqlCtx(String content, DataSourceRef ds) {
        return new ExecutionContext(content, "2026-06-20", 1, 30, "TEST", "SQL", ds);
    }

    @Test
    void type_isSQL() {
        assertThat(executor.type()).isEqualTo("SQL");
    }

    @Test
    void noDatasource_fallsBackToSimulated() {
        List<String> lines = new ArrayList<>();
        TaskExecutor.ExecutionResult r = executor.execute(sqlCtx("select 1", null), lines::add);

        assertThat(r.success()).isTrue();
        assertThat(String.join("\n", lines)).contains("未配置可用数据源");
    }

    @Test
    void unreachableDatasource_fallsBackToSimulated_doesNotThrow() {
        DataSourceRef ds = new DataSourceRef("orders_mysql", "MYSQL",
                "jdbc:mysql://10.0.0.20:3306/shop", "app", "***");
        List<String> lines = new ArrayList<>();
        TaskExecutor.ExecutionResult r = executor.execute(sqlCtx("select 1", ds), lines::add);

        // 无 mysql 驱动 / 连接失败 → 方案 A 回退模拟成功，不让调度崩
        assertThat(r.success()).isTrue();
        assertThat(String.join("\n", lines)).contains("模拟执行");
    }

    @Test
    void realH2Datasource_executesAndReportsAffectedRows_noResultSetRows() {
        // H2 内存库真实执行（worker 测试 classpath 含 H2）
        DataSourceRef ds = new DataSourceRef("h2mem", "H2",
                "jdbc:h2:mem:sqlexec_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
        String sql = "create table t(id int); insert into t values (1); insert into t values (2); select * from t";
        List<String> lines = new ArrayList<>();
        TaskExecutor.ExecutionResult r = executor.execute(sqlCtx(sql, ds), lines::add);

        String log = String.join("\n", lines);
        assertThat(r.success()).isTrue();
        assertThat(log).contains("连接成功");
        assertThat(log).contains("影响 1 行");           // insert 影响行数
        assertThat(log).contains("返回结果集");           // select 仅汇报，不打印行
        assertThat(log).doesNotContain("[stderr]");
    }

    @Test
    void emptyContent_returnsFailure() {
        TaskExecutor.ExecutionResult r = executor.execute(sqlCtx("   ", null), null);
        assertThat(r.success()).isFalse();
    }
}
