package com.dataweave.api;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

import com.dataweave.api.infrastructure.JwtUtil;
import com.dataweave.master.application.incident.IncidentBriefingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 069 US4 指挥中心后端契约（T032）：SSE 快照 + Last-Event-ID 补齐、战况播报数字与事实一致（SC-010）、
 * 对话前置校验（agent_disabled / 空发言 / 已收口）。独立 H2 库防串台；无真 LLM，只验确定性路径。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:dataweave-incident-us4-069;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
})
@DisplayName("Incident US4 指挥中心直播/播报/对话 契约（069）")
class IncidentUs4ContractIT {

    @LocalServerPort
    int port;
    @Autowired
    JwtUtil jwtUtil;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    IncidentBriefingService briefingService;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", JwtTestSupport.bearer(jwtUtil))
                .defaultHeader("X-Project-Id", "1")
                .responseTimeout(Duration.ofSeconds(20))
                .build();
    }

    /** 清空事故域三表 + 一条 FAILED 实例，给每个测试一个干净的 project=1 基线。 */
    private void resetIncidents() {
        jdbc.update("DELETE FROM incident_message");
        jdbc.update("DELETE FROM incident_instance");
        jdbc.update("DELETE FROM incident");
    }

    private UUID seedInstance() {
        UUID id = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update(
                "INSERT INTO task_instance (id, tenant_id, project_id, task_id, task_def_name, run_mode, " +
                "  state, attempt, error_message, log, created_at, updated_at, deleted, version) " +
                "VALUES (?, 1, 1, 990001, 'demo', 'NORMAL', 'FAILED', 1, 'boom', 'ERROR', ?, ?, 0, 0)",
                id, now, now);
        return id;
    }

    private long taskSeq = 990001L;

    /** open=true 时 open_key=唯一 task_def_id（守 uk_incident_open）；open=false 时 open_key=NULL（收口态）。 */
    private UUID seedIncident(String state, boolean open, LocalDateTime closedAt, String closeKind) {
        UUID id = UUID.randomUUID();
        UUID inst = seedInstance();
        long taskDefId = taskSeq++;
        Long openKey = open ? taskDefId : null;
        LocalDateTime now = LocalDateTime.now();
        jdbc.update(
                "INSERT INTO incident (id, tenant_id, project_id, task_def_id, task_def_name, first_instance_id, " +
                "  latest_instance_id, instance_count, trigger_source, state, open_key, auto_action_count, " +
                "  summary, close_kind, opened_at, closed_at, version, created_at, updated_at) " +
                "VALUES (?, 1, 1, ?, 'demo', ?, ?, 1, 'CRON', ?, ?, 0, '摘要', ?, ?, ?, 0, ?, ?)",
                id, taskDefId, inst, inst, state, openKey, closeKind, now, closedAt, now, now);
        return id;
    }

    private void seedMessage(UUID incidentId, long seq, String kind, String content) {
        jdbc.update(
                "INSERT INTO incident_message (id, incident_id, seq, kind, content, actor, created_at) " +
                "VALUES (?, ?, ?, ?, ?, 'system', ?)",
                UUID.randomUUID(), incidentId, seq, kind, content, LocalDateTime.now());
    }

    @Test
    @DisplayName("GET /stream 建连先发 snapshot（含 briefingStats）")
    void streamEmitsSnapshotFirst() {
        resetIncidents();
        seedIncident("NEEDS_HUMAN", true, null, null);

        Flux<String> body = client.get().uri("/api/incidents/stream")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange().expectStatus().isOk()
                .returnResult(String.class).getResponseBody();

        StepVerifier.create(body)
                .assertNext(s -> assertThat(s).contains("briefingStats").contains("incidents"))
                .thenCancel()
                .verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("GET /stream?lastEventId=incidentId:seq 快照后补齐持久化消息（瞬态不重放）")
    void streamReplaysPersistedMessages() {
        resetIncidents();
        UUID incidentId = seedIncident("ACTING", true, null, null);
        seedMessage(incidentId, 1, "AGENT_STEP", "诊断完成");
        seedMessage(incidentId, 2, "ACTION", "已重跑");

        Flux<String> body = client.get().uri("/api/incidents/stream?lastEventId={id}:0", incidentId.toString())
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange().expectStatus().isOk()
                .returnResult(String.class).getResponseBody();

        StepVerifier.create(body)
                .assertNext(s -> assertThat(s).contains("briefingStats"))      // 快照
                .assertNext(s -> assertThat(s).contains("诊断完成"))            // 补齐 seq=1
                .assertNext(s -> assertThat(s).contains("已重跑"))              // 补齐 seq=2
                .thenCancel()
                .verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("GET /briefing 数字与 incident 表事实一致（SC-010）")
    void briefingNumbersMatchFacts() {
        resetIncidents();
        seedIncident("ACTING", true, null, null);         // active + agentWorking
        seedIncident("NEEDS_HUMAN", true, null, null);    // active + needsHuman
        seedIncident("AWAITING_APPROVAL", true, null, null); // active + awaitingApproval
        seedIncident("RESOLVED", false, LocalDateTime.now(), "AUTO"); // resolvedToday（open_key NULL 不计 active）

        client.get().uri("/api/incidents/briefing")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.summaryLine").exists()
                .jsonPath("$.data.stats.active").isEqualTo(3)
                .jsonPath("$.data.stats.agentWorking").isEqualTo(1)
                .jsonPath("$.data.stats.needsHuman").isEqualTo(1)
                .jsonPath("$.data.stats.awaitingApproval").isEqualTo(1)
                .jsonPath("$.data.stats.resolvedToday").isEqualTo(1);
    }

    @Test
    @DisplayName("POST /{id}/chat 智能运维未启用 → incident.agent_disabled")
    void chatAgentDisabled() {
        resetIncidents();
        UUID incidentId = seedIncident("NEEDS_HUMAN", true, null, null);

        client.post().uri("/api/incidents/{id}/chat", incidentId)
                .bodyValue(new java.util.HashMap<>(java.util.Map.of("text", "为什么失败？")))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("incident.agent_disabled");
    }

    @Test
    @DisplayName("POST /{id}/chat 已收口事故 → incident.closed")
    void chatOnResolvedIncident() {
        resetIncidents();
        UUID incidentId = seedIncident("RESOLVED", false, LocalDateTime.now(), "AUTO");

        client.post().uri("/api/incidents/{id}/chat", incidentId)
                .bodyValue(new java.util.HashMap<>(java.util.Map.of("text", "复盘一下")))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("incident.closed");
    }

    @Test
    @DisplayName("briefingService.regenerate 无 LLM 时退确定性综述，数字仍实时")
    void briefingRegenerateDeterministicFallback() {
        resetIncidents();
        seedIncident("NEEDS_HUMAN", true, null, null);

        briefingService.regenerate(1L, 1L);

        var view = briefingService.get(1L, 1L);
        assertThat(view.summaryLine()).isNotBlank();
        assertThat(view.stats().active()).isEqualTo(1);
        assertThat(view.stats().needsHuman()).isEqualTo(1);
        assertThat(view.reportMd()).contains("接班报告");
    }
}
