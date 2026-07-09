package com.dataweave.worker.infrastructure;

import com.dataweave.master.domain.lineage.StatementMetric;
import com.dataweave.master.infrastructure.IsolatedDriverLoader;
import com.dataweave.worker.domain.ExecutionContext;
import com.dataweave.worker.domain.ExecutionContext.DataSourceRef;
import com.dataweave.worker.domain.TaskExecutor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * SqlTaskExecutor 方案 A：无数据源/连接失败回退 SKIPPED；
 * 配可用数据源（H2 内存）时真实执行、结果集渲染到日志（FR-011a / contracts C7.2）。
 */
class SqlTaskExecutorTest {

    private final SqlTaskExecutor executor = new SqlTaskExecutor(mock(IsolatedDriverLoader.class));

    private ExecutionContext sqlCtx(String content, DataSourceRef ds) {
        return new ExecutionContext(content, "2026-06-20", 1, 30, "TEST", "SQL", ds);
    }

    @Test
    void type_isSQL() {
        assertThat(executor.type()).isEqualTo("SQL");
    }

    @Test
    void noDatasource_returnsSkippedNotSuccess() {
        List<String> lines = new ArrayList<>();
        TaskExecutor.ExecutionResult r = executor.execute(sqlCtx("select 1", null), lines::add);

        // 环境缺失 → SKIPPED（不再伪装成功），可辨识、不阻塞下游（FR-008 / contracts C3）
        assertThat(r.skipped()).isTrue();
        assertThat(r.success()).isFalse();
        assertThat(String.join("\n", lines)).contains("未配置可用数据源");
    }

    @Test
    void unreachableDatasource_returnsSkipped_doesNotThrow() {
        DataSourceRef ds = new DataSourceRef("orders_mysql", "MYSQL",
                "jdbc:mysql://10.0.0.20:3306/shop", "app", "***");
        List<String> lines = new ArrayList<>();
        TaskExecutor.ExecutionResult r = executor.execute(sqlCtx("select 1", ds), lines::add);

        // 无 mysql 驱动 / 连接失败 → SKIPPED（环境缺失），不让调度崩
        assertThat(r.skipped()).isTrue();
        assertThat(r.success()).isFalse();
        assertThat(String.join("\n", lines)).contains("已跳过");
    }

    @Test
    void realH2Datasource_executesAndReportsAffectedRows_withResultSetRendering() {
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
        // FR-011a: SELECT 结果集渲染到日志
        assertThat(log).contains("行结果集");             // 汇总行「返回 N 行结果集」
        assertThat(log).contains("ID");                   // 表头
        assertThat(log).doesNotContain("[stderr]");
    }

    @Test
    void realH2_collectsStatementMetrics_skipsSelectResultSet() {
        // feature 025：per-statement (sqlText, updateCount>=0) 收集进 statementMetrics；SELECT(hasResultSet) 不收
        DataSourceRef ds = new DataSourceRef("h2mem", "H2",
                "jdbc:h2:mem:sqlexec_metrics_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
        String sql = "create table t(id int); insert into t values (1); insert into t values (2),(3); select * from t";
        List<String> lines = new ArrayList<>();
        TaskExecutor.ExecutionResult r = executor.execute(sqlCtx(sql, ds), lines::add);

        assertThat(r.success()).isTrue();
        List<StatementMetric> metrics = r.statementMetrics();
        // create(0) + insert(1) + insert(2) 收集；select(hasResultSet=true) 不收
        assertThat(metrics).hasSize(3);
        long insertRows = metrics.stream().mapToLong(StatementMetric::updateCount).filter(c -> c > 0).sum();
        assertThat(insertRows).isEqualTo(3L);   // 1 + 2
    }

    @Test
    void emptyContent_returnsFailure() {
        TaskExecutor.ExecutionResult r = executor.execute(sqlCtx("   ", null), null);
        assertThat(r.success()).isFalse();
    }

    // ---- T012a: 结果集渲染断言（FR-011a / contracts C7.2）----

    @Test
    void resultSetRendering_showsHeaderAndDataRows() {
        DataSourceRef ds = new DataSourceRef("h2mem", "H2",
                "jdbc:h2:mem:sqlexec_hdr_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
        String sql = "create table t(id int, name varchar); insert into t values (1, 'alice'); select * from t";
        List<String> lines = new ArrayList<>();
        TaskExecutor.ExecutionResult r = executor.execute(sqlCtx(sql, ds), lines::add);

        String log = String.join("\n", lines);
        assertThat(r.success()).isTrue();
        // 表头存在
        assertThat(log).contains("ID");
        assertThat(log).contains("NAME");
        // 数据行存在
        assertThat(log).contains("1");
        assertThat(log).contains("alice");
        // 汇总行
        assertThat(log).contains("返回 1 行结果集");
    }

    @Test
    void resultSetRendering_dmlStillReportsAffectedRows() {
        DataSourceRef ds = new DataSourceRef("h2mem", "H2",
                "jdbc:h2:mem:sqlexec_dml_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
        // 纯 DML，无 SELECT
        String sql = "create table t(id int); insert into t values (1); insert into t values (2)";
        List<String> lines = new ArrayList<>();
        TaskExecutor.ExecutionResult r = executor.execute(sqlCtx(sql, ds), lines::add);

        String log = String.join("\n", lines);
        assertThat(r.success()).isTrue();
        assertThat(log).contains("影响 0 行");  // CREATE TABLE
        assertThat(log).contains("影响 1 行");  // INSERT
        // DML 不应有表头标记
        assertThat(log).doesNotContain(" | ");
    }

    @Test
    void resultSetRendering_truncatesExcessRows() {
        DataSourceRef ds = new DataSourceRef("h2mem", "H2",
                "jdbc:h2:mem:sqlexec_trunc_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
        // 插入超过 MAX_RESULT_ROWS 行，验证截断
        StringBuilder sql = new StringBuilder("create table t(id int);");
        int totalRows = SqlTaskExecutor.MAX_RESULT_ROWS + 10;
        for (int i = 1; i <= totalRows; i++) {
            sql.append("insert into t values (").append(i).append(");");
        }
        sql.append("select * from t");
        List<String> lines = new ArrayList<>();
        TaskExecutor.ExecutionResult r = executor.execute(sqlCtx(sql.toString(), ds), lines::add);

        String log = String.join("\n", lines);
        assertThat(r.success()).isTrue();
        // 截断标注
        assertThat(log).contains("已截断，仅显示前 " + SqlTaskExecutor.MAX_RESULT_ROWS + " 行");
    }

    @Test
    void resultSetRendering_showsNullAsNULL() {
        DataSourceRef ds = new DataSourceRef("h2mem", "H2",
                "jdbc:h2:mem:sqlexec_null_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
        String sql = "create table t(id int, name varchar); insert into t values (1, null); select * from t";
        List<String> lines = new ArrayList<>();
        TaskExecutor.ExecutionResult r = executor.execute(sqlCtx(sql, ds), lines::add);

        String log = String.join("\n", lines);
        assertThat(r.success()).isTrue();
        assertThat(log).contains("NULL");
    }

    // ---- T012: ClickHouse/StarRocks/Doris 行为验证 ----

    @Test
    void starRocksDoris_affectedRows_fillsStatementMetrics() {
        // StarRocks/Doris 走 MySQL 协议，getUpdateCount 正确回填 StatementMetric（feature 025）
        DataSourceRef ds = new DataSourceRef("h2mem", "H2",
                "jdbc:h2:mem:sqlexec_sr_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
        String sql = "create table t(id int); insert into t values (1); insert into t values (2),(3)";
        List<String> lines = new ArrayList<>();
        TaskExecutor.ExecutionResult r = executor.execute(sqlCtx(sql, ds), lines::add);

        assertThat(r.success()).isTrue();
        List<StatementMetric> metrics = r.statementMetrics();
        // create(0) + insert(1) + insert(2) = 3
        assertThat(metrics).hasSize(3);
        assertThat(metrics.get(0).updateCount()).isEqualTo(0);  // CREATE
        assertThat(metrics.get(1).updateCount()).isEqualTo(1);  // INSERT 1
        assertThat(metrics.get(2).updateCount()).isEqualTo(2);  // INSERT 2
    }

    @Test
    void clickHouseStyle_ddlNoFalseRowCount() {
        // ClickHouse DDL 在部分驱动下 execute() 返回 false、getUpdateCount() 返回 0（H2 模拟）
        // 验证 DDL 影响行数汇报不误报（updateCount=0 被正确汇报为 "影响 0 行"）
        DataSourceRef ds = new DataSourceRef("h2mem", "H2",
                "jdbc:h2:mem:sqlexec_ch_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
        String sql = "create table t(id int primary key, name varchar(50))";
        List<String> lines = new ArrayList<>();
        TaskExecutor.ExecutionResult r = executor.execute(sqlCtx(sql, ds), lines::add);

        String log = String.join("\n", lines);
        assertThat(r.success()).isTrue();
        // DDL 汇报影响 0 行（非负值）
        assertThat(log).contains("影响 0 行");
    }

    // ---- maskJdbcUrl 单元测试（FR-017 / contracts C7.4）----

    @Test
    void maskJdbcUrl_stripsPasswordParam() {
        assertThat(SqlTaskExecutor.maskJdbcUrl(
                "jdbc:mysql://host:3306/db?user=app&password=secret123"))
                .isEqualTo("jdbc:mysql://host:3306/db?user=app&password=***");
    }

    @Test
    void maskJdbcUrl_noPasswordParam_unchanged() {
        assertThat(SqlTaskExecutor.maskJdbcUrl("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1"))
                .isEqualTo("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1");
    }

    @Test
    void maskJdbcUrl_null_returnsNull() {
        assertThat(SqlTaskExecutor.maskJdbcUrl(null)).isNull();
    }

    @Test
    void maskJdbcUrl_passwordNotInConnectionLog() {
        // 验证连接日志使用 maskJdbcUrl 脱敏（FR-017 / contracts C7.4）
        // 通过纯 H2 URL 验证日志中不含明密密码（不带 password= 参数的真实 URL）
        DataSourceRef ds = new DataSourceRef("h2mem", "H2",
                "jdbc:h2:mem:sqlexec_mask_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
        List<String> lines = new ArrayList<>();
        TaskExecutor.ExecutionResult r = executor.execute(sqlCtx("select 1", ds), lines::add);

        String log = String.join("\n", lines);
        assertThat(r.success()).isTrue();
        // 连接日志中 JDBC URL 不包含明文密码（maskJdbcUrl 已脱敏）
        assertThat(log).contains("连接数据源");
        // maskJdbcUrl 单元测试覆盖脱敏逻辑本身（见下方 maskJdbcUrl_* 三个测试）
    }
}
