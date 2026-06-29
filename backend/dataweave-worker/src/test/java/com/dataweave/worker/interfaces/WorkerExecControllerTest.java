package com.dataweave.worker.interfaces;

import com.dataweave.worker.domain.ExecutionContext;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C4.2 数据源 over-wire 测试（contracts C4.2 / FR-016）：distributed exec body 的 datasource 字段
 * 经 {@code WorkerExecController.buildContextFromBody} 反序列化为完整 ExecutionContext——
 * SQL 携带 DataSourceRef / SPARK 携带 SparkSubmitRef / PYTHON 落盘 / 无数据源留空（执行器侧判 SKIPPED）。
 */
class WorkerExecControllerTest {

    @Test
    @SuppressWarnings("unchecked")
    void sqlDatasourceOverWire_deserializedIntoCtx() {
        Map<String, Object> ds = Map.of("taskType", "SQL", "name", "wh", "typeCode", "POSTGRESQL",
                "jdbcUrl", "jdbc:postgresql://h/db", "username", "u", "password", "p");
        Map<String, Object> body = Map.of("datasource", ds);

        ExecutionContext ctx = WorkerExecController.buildContextFromBody(body, "select 1", null, 1, 10, "SQL");

        assertThat(ctx.datasource()).isNotNull();
        assertThat(ctx.datasource().jdbcUrl()).isEqualTo("jdbc:postgresql://h/db");
        assertThat(ctx.datasource().username()).isEqualTo("u");
        assertThat(ctx.datasource().typeCode()).isEqualTo("POSTGRESQL");
    }

    @Test
    @SuppressWarnings("unchecked")
    void sparkDatasourceOverWire_deserializedIntoCtx() {
        Map<String, Object> ds = Map.of("taskType", "SPARK", "sparkHome", "/opt/spark",
                "master", "local[*]", "deployMode", "client");
        Map<String, Object> body = Map.of("datasource", ds);

        ExecutionContext ctx = WorkerExecController.buildContextFromBody(body, "print('x')", null, 1, 10, "SPARK");

        assertThat(ctx.spark()).isNotNull();
        assertThat(ctx.spark().sparkHome()).isEqualTo("/opt/spark");
        assertThat(ctx.spark().master()).isEqualTo("local[*]");
        assertThat(ctx.spark().deployMode()).isEqualTo("client");
    }

    @Test
    @SuppressWarnings("unchecked")
    void pythonDatasourceOverWire_landedToTempFile() throws Exception {
        Map<String, Object> ds = Map.of("taskType", "PYTHON", "pythonConfigJson", "{\"type\":\"H2\",\"host\":\"h\"}");
        Map<String, Object> body = Map.of("datasource", ds);

        ExecutionContext ctx = WorkerExecController.buildContextFromBody(body, "print('x')", null, 1, 10, "PYTHON");

        assertThat(ctx.pythonConfigPath()).isNotNull();
        String landed = Files.readString(Path.of(ctx.pythonConfigPath()), StandardCharsets.UTF_8);
        assertThat(landed).contains("H2");
    }

    @Test
    void noDatasourceInBody_emptyCtxFields() {
        Map<String, Object> body = Map.of();

        ExecutionContext ctx = WorkerExecController.buildContextFromBody(body, "x", null, 1, 10, "SHELL");

        assertThat(ctx.datasource()).isNull();
        assertThat(ctx.spark()).isNull();
        assertThat(ctx.pythonConfigPath()).isNull();
        assertThat(ctx.taskType()).isEqualTo("SHELL");
    }

    /** distributed 腿：SPARK 内容形态（sparkMode/jarRef/mainClass）经 body 顶层透传 → SparkSubmitRef。 */
    @Test
    void sparkContentFormOverWire_sparkModeJarMainClassDeserialized() {
        Map<String, Object> ds = Map.of("taskType", "SPARK", "sparkHome", "/opt/spark", "master", "yarn");
        Map<String, Object> body = Map.of("datasource", ds,
                "sparkMode", "jar", "jarRef", "/tmp/app.jar", "mainClass", "com.x.Main");

        ExecutionContext ctx = WorkerExecController.buildContextFromBody(body, "", null, 1, 10, "SPARK");

        assertThat(ctx.spark()).isNotNull();
        assertThat(ctx.spark().sparkMode()).isEqualTo("jar");
        assertThat(ctx.spark().jarPath()).isEqualTo("/tmp/app.jar");
        assertThat(ctx.spark().mainClass()).isEqualTo("com.x.Main");
        assertThat(ctx.spark().master()).isEqualTo("yarn");
    }

    /** 无数据源的 SPARK 任务：内容形态仍须带（sparkHome/master 缺 → 执行器判 SKIPPED，但不丢 sparkMode）。 */
    @Test
    void sparkNoDatasourceButHasContentForm_sparkModeStillCarried() {
        Map<String, Object> body = Map.of("sparkMode", "spark-sql");

        ExecutionContext ctx = WorkerExecController.buildContextFromBody(body, "select 1", null, 1, 10, "SPARK");

        assertThat(ctx.spark()).isNotNull();
        assertThat(ctx.spark().sparkMode()).isEqualTo("spark-sql");
        assertThat(ctx.spark().master()).isNull();
    }
}
