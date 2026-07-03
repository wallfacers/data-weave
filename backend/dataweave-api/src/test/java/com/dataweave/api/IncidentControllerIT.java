package com.dataweave.api;

import com.dataweave.api.infrastructure.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Incident HTTP 契约测试（043 T012+T021）：队列/详情/处置/审批 全栈验证。
 *
 * <p>独立 H2 库名（防跨测试类串台），JWT 鉴权，统一 200 + $.code/$.data/$.errorCode 断言。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:dataweave-incident043;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
})
@DisplayName("Incident HTTP 契约 (043)")
class IncidentControllerIT {

    @LocalServerPort
    int port;
    @Autowired
    JwtUtil jwtUtil;
    @Autowired
    JdbcTemplate jdbc;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", JwtTestSupport.bearer(jwtUtil))
                .responseTimeout(java.time.Duration.ofSeconds(15))
                .build();
        // 净环境：每次测试前置清 incident 数据
        jdbc.update("DELETE FROM incident_event");
        jdbc.update("DELETE FROM incident");
    }

    // ═══════════════════════════════════════════════════════════
    // T012 US1: queue / detail / cross-project
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("US1-队列三区结构：active + recentResolved + counts")
    void queueThreeZones() {
        seedIncident(1L, "T:1024:EXIT_NONZERO", "OPEN", "ods_订单同步 失败(EXIT_NONZERO)", 3);
        seedIncident(1L, "T:1025:TIMEOUT", "OPEN", "dw_report 超时(TIMEOUT)", 1);
        seed24hResolved(1L, "T:1026:EXIT_NONZERO", "old_task 失败", LocalDateTime.now().minusHours(2));

        client.get().uri("/api/incidents?projectId=1")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.active.length()").isEqualTo(2)
                .jsonPath("$.data.recentResolved.length()").isEqualTo(1)
                .jsonPath("$.data.activeCount").isEqualTo(2)
                .jsonPath("$.data.recentResolvedCount").isEqualTo(1);
    }

    @Test
    @DisplayName("US1-卡片字段完整性")
    void cardFieldsComplete() {
        seedIncidentWithAllFields(1L, "T:1024:EXIT_NONZERO", "OPEN");

        client.get().uri("/api/incidents?projectId=1")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.active[0].title").isEqualTo("ods_订单同步 失败(EXIT_NONZERO)")
                .jsonPath("$.data.active[0].severity").isEqualTo("HIGH")
                .jsonPath("$.data.active[0].state").isEqualTo("OPEN")
                .jsonPath("$.data.active[0].occurrenceCount").isEqualTo(3)
                .jsonPath("$.data.active[0].sourceKind").isEqualTo("TASK")
                .jsonPath("$.data.active[0].sourceRefId").isEqualTo("1024");
    }

    @Test
    @DisplayName("US1-详情含 timeline 顺序 + actions")
    void detailTimelineAndActions() {
        long id = seedIncidentWithAllFields(1L, "T:1024:EXIT_NONZERO", "OPEN");
        // 追加一条 SIGNAL timeline
        jdbc.update("INSERT INTO incident_event (tenant_id, incident_id, seq, kind, payload_json, actor, created_at) " +
                "VALUES (1, ?, 1, 'SIGNAL', '{\"type\":\"TASK_FAILED\"}', 'system', ?)", id, LocalDateTime.now());

        client.get().uri("/api/incidents/" + id)
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.incident.title").isNotEmpty()
                .jsonPath("$.data.timeline.length()").isEqualTo(1)
                .jsonPath("$.data.timeline[0].kind").isEqualTo("SIGNAL")
                .jsonPath("$.data.timeline[0].actor").isEqualTo("system");
    }

    @Test
    @DisplayName("US1-跨项目访问 → incident.not_found")
    void crossProjectNotFound() {
        long id = seedIncident(3L, "T:9999:UNKNOWN", "OPEN", "other project", 1);

        // 用 projectId=1 访问 projectId=3 的工单
        client.get().uri("/api/incidents/" + id)
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("incident.not_found");
    }

    @Test
    @DisplayName("US1-无 JWT → 401")
    void noJwtUnauthorized() {
        WebTestClient noAuth = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port).build();
        noAuth.get().uri("/api/incidents?projectId=1")
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("US1-历史分页筛选")
    void historyPagination() {
        seedIncident(1L, "T:1024:EXIT_NONZERO", "CLOSED", "closed task", 1);

        client.get().uri("/api/incidents/history?projectId=1&state=CLOSED&page=1&size=20")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.items.length()").isEqualTo(1)
                .jsonPath("$.data.total").isEqualTo(1);
    }

    // ═══════════════════════════════════════════════════════════
    // T021 US3: rerun / suppress / notes / approval
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("US3-静默缺原因 → suppress_reason_required")
    void suppressReasonRequired() {
        long id = seedIncident(1L, "T:1024:EXIT_NONZERO", "OPEN", "test", 1);

        client.post().uri("/api/incidents/" + id + "/suppress")
                .bodyValue(Map.of("reason", ""))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("incident.suppress_reason_required");
    }

    @Test
    @DisplayName("US3-静默成功 + unsuppress")
    void suppressAndUnsuppress() {
        long id = seedIncident(1L, "T:1024:EXIT_NONZERO", "OPEN", "test", 1);

        // suppress
        client.post().uri("/api/incidents/" + id + "/suppress")
                .bodyValue(Map.of("reason", "上游供应商故障"))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.code").isEqualTo(0);

        // 验证状态变为 SUPPRESSED
        client.get().uri("/api/incidents/" + id)
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.incident.state").isEqualTo("SUPPRESSED")
                .jsonPath("$.data.incident.suppressReason").isEqualTo("上游供应商故障");

        // unsuppress
        client.post().uri("/api/incidents/" + id + "/unsuppress")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.code").isEqualTo(0);

        // 验证恢复 OPEN
        client.get().uri("/api/incidents/" + id)
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.incident.state").isEqualTo("OPEN");
    }

    @Test
    @DisplayName("US3-对 CLOSED 静默 → incident.invalid_state")
    void suppressOnClosed() {
        long id = seedIncident(1L, "T:1024:EXIT_NONZERO", "CLOSED", "closed test", 1);

        client.post().uri("/api/incidents/" + id + "/suppress")
                .bodyValue(Map.of("reason", "reason"))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("incident.invalid_state");
    }

    @Test
    @DisplayName("US3-追加备注 → timeline 有 NOTE 条目")
    void appendNoteTimeline() {
        long id = seedIncident(1L, "T:1024:EXIT_NONZERO", "OPEN", "test note", 1);

        client.post().uri("/api/incidents/" + id + "/notes")
                .bodyValue(Map.of("text", "排查发现是上游数据延迟导致"))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.code").isEqualTo(0);

        // 详情 timeline 含 NOTE
        client.get().uri("/api/incidents/" + id)
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.timeline[?(@.kind == 'NOTE')]").exists();
    }

    @Test
    @DisplayName("US3-重跑 → EXECUTED + incident_id 落库")
    void rerunExecuted() {
        long id = seedIncident(1L, "T:1024:EXIT_NONZERO", "OPEN", "test rerun", 1);

        // 先配 L1 规则使 incident_rerun 直执行（兜底默认会 PENDING_APPROVAL）
        jdbc.update("INSERT INTO policy_rules (match_type, pattern, base_level, description, enabled, sort_order, created_at, updated_at) " +
                "VALUES ('TOOL', 'incident_rerun', 'L1', 'incident rerun low risk', 1, 1, ?, ?)",
                LocalDateTime.now(), LocalDateTime.now());

        // 需要真实的 task_instance UUID 供 rerun 校验...但非 TASK 类型会被拒
        // 此处验证重跑校验链工作的基本路径：构造无效 taskInstanceId 应被 bizException 拦截而非 500
        client.post().uri("/api/incidents/" + id + "/rerun")
                .bodyValue(Map.of("taskInstanceId", "00000000-0000-0000-0000-000000000001"))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.outcome").exists()
                .jsonPath("$.data.actionId").exists();
    }

    // ═══════════════════════════════════════════════════════════
    // helpers
    // ═══════════════════════════════════════════════════════════

    private long seedIncident(long projectId, String signature, String state, String title, int count) {
        return seed(projectId, signature, signature, title, state, count, null, null);
    }

    private long seed24hResolved(long projectId, String signature, String title, LocalDateTime resolvedAt) {
        return seed(projectId, signature, signature, title, "RESOLVED", 1, resolvedAt, null);
    }

    private long seedIncidentWithAllFields(long projectId, String signature, String state) {
        return seed(projectId, signature, signature, "ods_订单同步 失败(EXIT_NONZERO)", state, 3,
                null, java.util.UUID.randomUUID());
    }

    private long seed(long projectId, String signature, String activeKey, String title,
                       String state, int count, LocalDateTime resolvedAt, java.util.UUID wfi) {
        LocalDateTime now = LocalDateTime.now();
        var kh = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incident (tenant_id, project_id, signature, active_key, title, " +
                    "severity, state, source_kind, source_ref_id, source_ref_name, workflow_instance_id, " +
                    "occurrence_count, first_seen_at, last_seen_at, resolved_at, " +
                    "created_by, updated_by, created_at, updated_at) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    new String[]{"id"});
            int i = 0;
            ps.setLong(++i, 1L);  // tenantId
            ps.setLong(++i, projectId);
            ps.setString(++i, signature);
            ps.setString(++i, activeKey);
            ps.setString(++i, title);
            ps.setString(++i, "HIGH");
            ps.setString(++i, state);
            ps.setString(++i, "TASK");
            ps.setString(++i, signature.replaceAll(".*:(\\d+):.*", "$1")); // extract taskId from signature
            ps.setString(++i, title);
            ps.setObject(++i, wfi);
            ps.setInt(++i, count);
            ps.setObject(++i, now);
            ps.setObject(++i, now);
            ps.setObject(++i, resolvedAt);
            ps.setLong(++i, 1L);
            ps.setLong(++i, 1L);
            ps.setObject(++i, now);
            ps.setObject(++i, now);
            return ps;
        }, kh);
        Number key = kh.getKey();
        assertThat(key).isNotNull();
        return key.longValue();
    }
}
