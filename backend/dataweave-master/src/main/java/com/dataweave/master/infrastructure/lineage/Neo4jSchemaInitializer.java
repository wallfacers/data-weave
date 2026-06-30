package com.dataweave.master.infrastructure.lineage;

import java.util.List;

import org.neo4j.driver.Driver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动期幂等创建 neo4j 约束 / 索引（data-model.md §3，FR-006）。
 *
 * <p>对每个节点唯一键建 {@code CONSTRAINT ... IS UNIQUE}（合成 key 属性 dsKey/tableKey/columnKey/metricKey/taskKey/instanceId），
 * 为 {@code tenantId/projectId} 建复合索引（按租户/项目隔离查询加速）。
 *
 * <p>韧性（FR-007）：neo4j 不可达时记 WARN 日志、不阻断应用启动。{@link Order} 最高优先级，
 * 先于 {@code Neo4jLineageSeeder}（后者依赖约束保证并发去重正确性）。
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@Component
public class Neo4jSchemaInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(Neo4jSchemaInitializer.class);

    /** 合成 key 唯一约束（单属性身份，承载多列复合身份与去重规范化）。 */
    private static final List<String> CONSTRAINTS = List.of(
            "CREATE CONSTRAINT datasource_key IF NOT EXISTS FOR (d:Datasource) REQUIRE d.dsKey IS UNIQUE",
            "CREATE CONSTRAINT table_key IF NOT EXISTS FOR (t:Table) REQUIRE t.tableKey IS UNIQUE",
            "CREATE CONSTRAINT column_key IF NOT EXISTS FOR (c:Column) REQUIRE c.columnKey IS UNIQUE",
            "CREATE CONSTRAINT metric_key IF NOT EXISTS FOR (m:Metric) REQUIRE m.metricKey IS UNIQUE",
            "CREATE CONSTRAINT task_key IF NOT EXISTS FOR (n:Task) REQUIRE n.taskKey IS UNIQUE",
            "CREATE CONSTRAINT taskrun_key IF NOT EXISTS FOR (r:TaskRun) REQUIRE r.instanceId IS UNIQUE");

    /** 租户/项目隔离索引（FR-006，加速 scope 查询）。 */
    private static final List<String> INDEXES = List.of(
            "CREATE INDEX ds_scope     IF NOT EXISTS FOR (d:Datasource) ON (d.tenantId, d.projectId)",
            "CREATE INDEX table_scope  IF NOT EXISTS FOR (t:Table)      ON (t.tenantId, t.projectId)",
            "CREATE INDEX col_scope    IF NOT EXISTS FOR (c:Column)     ON (c.tenantId, c.projectId)",
            "CREATE INDEX task_scope   IF NOT EXISTS FOR (n:Task)       ON (n.tenantId, n.projectId)",
            "CREATE INDEX metric_scope IF NOT EXISTS FOR (m:Metric)     ON (m.tenantId, m.projectId)");

    private final Driver driver;

    public Neo4jSchemaInitializer(Driver driver) {
        this.driver = driver;
    }

    /** 幂等执行所有约束 / 索引创建（IF NOT EXISTS）。供 ApplicationRunner 与测试 harness 复用。 */
    public void initialize() {
        try (var session = driver.session()) {
            for (String cypher : CONSTRAINTS) {
                session.run(cypher).consume();
            }
            for (String cypher : INDEXES) {
                session.run(cypher).consume();
            }
            log.debug("neo4j lineage schema initialized ({} constraints, {} indexes)",
                    CONSTRAINTS.size(), INDEXES.size());
        } catch (Exception e) {
            // neo4j 不可达：降级记日志，不阻断启动（血缘是增强）
            log.warn("neo4j schema init skipped (unreachable or error): {}", e.getMessage());
        }
    }

    @Override
    public void run(ApplicationArguments args) {
        // 后台守护线程异步初始化：neo4j 不可达时（driver 连接获取超时 5s）不阻塞应用启动序列，
        // 也不扰动同上下文内调度器等时序敏感组件。约束/索引均 IF NOT EXISTS，幂等可后补。
        Thread t = new Thread(this::initialize, "neo4j-schema-init");
        t.setDaemon(true);
        t.start();
    }
}
