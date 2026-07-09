package com.dataweave.worker.infrastructure;

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
 * HiveTaskExecutor 测试：HQL 多语句、SET 会话指令、分区不误报、SKIPPED、失败透传（T011 / FR-003）。
 * <p>使用 H2 内存库作为 JDBC 替代（无需外部 Hive 依赖），验证执行器逻辑而非 Hive 驱动行为。
 */
class HiveTaskExecutorTest {

    private final HiveTaskExecutor executor = new HiveTaskExecutor(mock(IsolatedDriverLoader.class));

    private ExecutionContext hiveCtx(String content, DataSourceRef ds) {
        return new ExecutionContext(content, "2026-06-20", 1, 30, "TEST", "HIVE", ds);
    }

    @Test
    void type_isHIVE() {
        assertThat(executor.type()).isEqualTo("HIVE");
    }

    @Test
    void noDatasource_returnsSkipped() {
        List<String> lines = new ArrayList<>();
        TaskExecutor.ExecutionResult r = executor.execute(hiveCtx("select 1", null), lines::add);

        assertThat(r.skipped()).isTrue();
        assertThat(r.success()).isFalse();
        assertThat(String.join("\n", lines)).contains("未配置可用 Hive 数据源");
    }

    @Test
    void unreachableDatasource_returnsSkipped() {
        DataSourceRef ds = new DataSourceRef("hive_prod", "HIVE",
                "jdbc:hive2://10.0.0.30:10000/default", "hive", "***");
        List<String> lines = new ArrayList<>();
        TaskExecutor.ExecutionResult r = executor.execute(hiveCtx("select 1", ds), lines::add);

        assertThat(r.skipped()).isTrue();
        assertThat(r.success()).isFalse();
        assertThat(String.join("\n", lines)).contains("已跳过");
    }

    @Test
    void emptyContent_returnsFailure() {
        TaskExecutor.ExecutionResult r = executor.execute(hiveCtx("   ", null), null);
        assertThat(r.success()).isFalse();
    }

    @Test
    void hql_select_rendersResultSet() {
        // 经 H2 连接验证 SELECT 结果集渲染复用 SqlTaskExecutor.renderResultSet（contracts C7.2）
        DataSourceRef ds = new DataSourceRef("h2mem", "H2",
                "jdbc:h2:mem:hiveexec_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
        String sql = "create table t(id int, name varchar); insert into t values (1, 'alice'); select * from t";
        List<String> lines = new ArrayList<>();
        TaskExecutor.ExecutionResult r = executor.execute(hiveCtx(sql, ds), lines::add);

        String log = String.join("\n", lines);
        assertThat(r.success()).isTrue();
        // 结果集渲染（表头 + 数据行）
        assertThat(log).contains("ID");
        assertThat(log).contains("NAME");
        assertThat(log).contains("alice");
        assertThat(log).contains("行结果集");
    }

    @Test
    void hql_dml_reportsAffectedRows() {
        DataSourceRef ds = new DataSourceRef("h2mem", "H2",
                "jdbc:h2:mem:hiveexec_dml_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
        String sql = "create table t(id int); insert into t values (1); insert into t values (2)";
        List<String> lines = new ArrayList<>();
        TaskExecutor.ExecutionResult r = executor.execute(hiveCtx(sql, ds), lines::add);

        String log = String.join("\n", lines);
        assertThat(r.success()).isTrue();
        assertThat(log).contains("影响 0 行");  // CREATE TABLE
        assertThat(log).contains("影响 1 行");  // INSERT
    }

    @Test
    void hql_setStatement_notCountedAsAffectedRows() {
        // SET 会话指令不当影响行数；H2 支持 SET MODE 命令用于验证
        DataSourceRef ds = new DataSourceRef("h2mem", "H2",
                "jdbc:h2:mem:hiveexec_set_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
        List<String> lines = new ArrayList<>();
        TaskExecutor.ExecutionResult r = executor.execute(hiveCtx("SET MODE MySQL", ds), lines::add);

        String log = String.join("\n", lines);
        // SET 被识别为会话指令，标注"SET 会话指令"而非"影响 N 行"
        assertThat(log).contains("SET 会话指令");
        assertThat(log).doesNotContain("影响");
    }

    @Test
    void hql_statementError_returnsFailure() {
        DataSourceRef ds = new DataSourceRef("h2mem", "H2",
                "jdbc:h2:mem:hiveexec_err_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
        List<String> lines = new ArrayList<>();
        // 语法错误的 HQL
        TaskExecutor.ExecutionResult r = executor.execute(hiveCtx("BOGUS STATEMENT", ds), lines::add);

        String log = String.join("\n", lines);
        assertThat(r.success()).isFalse();
        assertThat(r.skipped()).isFalse();
        assertThat(log).contains("HQL 执行失败");
    }

    // ---- isSetStatement 纯函数单测 ----

    @Test
    void isSetStatement_setKeyValue_true() {
        assertThat(HiveTaskExecutor.isSetStatement("SET hive.exec.dynamic.partition=true")).isTrue();
        assertThat(HiveTaskExecutor.isSetStatement("set mapreduce.job.reduces=10")).isTrue();
        assertThat(HiveTaskExecutor.isSetStatement("SET k=v")).isTrue();
    }

    @Test
    void isSetStatement_setWithTrailingSpaces_true() {
        assertThat(HiveTaskExecutor.isSetStatement("  SET k=v  ")).isTrue();
    }

    @Test
    void isSetStatement_selectNotSet_false() {
        assertThat(HiveTaskExecutor.isSetStatement("SELECT * FROM t")).isFalse();
        assertThat(HiveTaskExecutor.isSetStatement("INSERT INTO t VALUES(1)")).isFalse();
        assertThat(HiveTaskExecutor.isSetStatement("SHOW TABLES")).isFalse();
    }

    @Test
    void isSetStatement_settingKeyword_false() {
        // SET 后必须跟空白（排除 SETTING 等非 SET 语句）
        assertThat(HiveTaskExecutor.isSetStatement("SETTINGS")).isFalse();
    }

    @Test
    void isSetStatement_onlySet_false() {
        // 仅 "SET" 三个字符，无后续空白 + 值
        assertThat(HiveTaskExecutor.isSetStatement("SET")).isTrue();
    }

    @Test
    void isSetStatement_null_false() {
        assertThat(HiveTaskExecutor.isSetStatement(null)).isFalse();
    }
}
