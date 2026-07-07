package com.dataweave.api;

import com.dataweave.master.application.lineage.agent.AgentExtraction;
import com.dataweave.master.application.lineage.agent.LineageEnrichmentTrigger;
import com.dataweave.master.application.lineage.agent.LlmAgentClient;
import com.dataweave.master.domain.lineage.LineageAgentConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 053 异步富化 neo4j 直连 IT（T018，契约 C3）。放 api 模块（@SpringBootApplication 所在，context 可起）。
 * @TestConfiguration 用 @Primary 假 LlmAgentClient（固定返回，不真外呼），真 neo4j store 验证：
 * SCRIPT_AGENT 边写入 + 审计落 + Calcite 成功不外呼 + 未启用零外呼。
 */
@SpringBootTest
@ActiveProfiles("h2")
@Import(LineageAgentEnricherIT.FixedLlmClientConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:dataweave-lineage-agent-it-053;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
})
@DisplayName("Lineage Agent Enricher 异步富化 (053, neo4j 直连)")
class LineageAgentEnricherIT {

    static final AtomicInteger EXTRACT_CALLS = new AtomicInteger(0);

    private static final long TASK_ID = 9001L;
    private static final String SCRIPT =
            "INSERT INTO dw.result SELECT * FROM src\n# api.write(dw.private)";

    @Autowired
    LineageEnrichmentTrigger trigger;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    Driver driver;

    @TestConfiguration
    static class FixedLlmClientConfig {
        @Bean
        @Primary
        LlmAgentClient fixedLlmClient() {
            // 假客户端：固定返回 reads=[src] writes=[dw.private]（脚本均字面命中，防幻觉通过），不真外呼
            return new LlmAgentClient(List.of(), null) {
                @Override
                public LlmAgentClient.CallResult extract(LineageAgentConfig cfg, String content, String taskType) {
                    EXTRACT_CALLS.incrementAndGet();
                    return new LlmAgentClient.CallResult(new AgentExtraction(
                            List.of("src"), List.of("dw.private"), List.of(), 0.9, cfg.model()), 88, null);
                }
            };
        }
    }

    @BeforeEach
    void setUp() {
        cleanNeo4j();
        jdbc.update("DELETE FROM lineage_agent_call");
        jdbc.update("DELETE FROM lineage_agent_config");
        jdbc.update("DELETE FROM task_def WHERE id = ?", TASK_ID);
        // seed 启用的配置（免鉴权——假 client 不真外呼）
        jdbc.update("INSERT INTO lineage_agent_config (tenant_id, project_id, protocol, base_url, model, "
                + "enabled, timeout_ms, rate_limit_per_min, max_columns, deleted, version) "
                + "VALUES (1, 1, 'OPENAI', 'https://x', 'm', 1, 30000, 60, 2000, 0, 0)");
        jdbc.update("INSERT INTO task_def (id, tenant_id, project_id, name, type, content, status) "
                + "VALUES (?, 1, 1, 'etl', 'PYTHON', ?, 'ONLINE')", TASK_ID, SCRIPT);
        EXTRACT_CALLS.set(0);
    }

    @Test
    @DisplayName("异步富化写入 SCRIPT_AGENT 边 + 落审计（FR-004b / C3）")
    void enrichWritesScriptAgentEdgeAndAudit() {
        trigger.request(1L, 1L, TASK_ID, "PYTHON", false, null, null);
        awaitCondition(() -> countScriptAgentEdges() > 0, 10);
        assertThat(countScriptAgentEdges()).isGreaterThan(0);
        Integer calls = jdbc.queryForObject(
                "SELECT count(*) FROM lineage_agent_call WHERE task_def_id = ?", Integer.class, TASK_ID);
        assertThat(calls).isEqualTo(1);
        assertThat(EXTRACT_CALLS.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Calcite 正常解析的 SQL 不外呼（D7 / FR-001）")
    void calciteParsedSqlSkipsAgent() throws Exception {
        trigger.request(1L, 1L, TASK_ID, "SQL", true, null, null);
        Thread.sleep(3000);  // 等异步窗口确认旁路
        assertThat(EXTRACT_CALLS.get()).isEqualTo(0);
        Integer calls = jdbc.queryForObject(
                "SELECT count(*) FROM lineage_agent_call WHERE task_def_id = ?", Integer.class, TASK_ID);
        assertThat(calls).isEqualTo(0);
    }

    @Test
    @DisplayName("未启用项目零外呼（FR-019 / SC-005）")
    void disabledProjectBypasses() throws Exception {
        jdbc.update("UPDATE lineage_agent_config SET enabled = 0 WHERE tenant_id = 1 AND project_id = 1");
        trigger.request(1L, 1L, TASK_ID, "PYTHON", false, null, null);
        Thread.sleep(3000);
        assertThat(EXTRACT_CALLS.get()).isEqualTo(0);
        assertThat(countScriptAgentEdges()).isEqualTo(0);
    }

    private long countScriptAgentEdges() {
        try (Session s = driver.session()) {
            return s.run("MATCH ()-[r]->() WHERE r.source = 'SCRIPT_AGENT' RETURN count(r) AS c")
                    .single().get("c").asLong();
        }
    }

    private void cleanNeo4j() {
        try (Session s = driver.session()) {
            s.run("MATCH (n) DETACH DELETE n").consume();
        }
    }

    private static void awaitCondition(BooleanSupplier cond, int timeoutSec) {
        long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return;
            try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }
}
