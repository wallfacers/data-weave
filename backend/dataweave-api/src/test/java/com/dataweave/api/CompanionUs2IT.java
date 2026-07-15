package com.dataweave.api;

import java.time.Duration;

import com.dataweave.api.infrastructure.JwtUtil;
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
 * 071 US2 巡检/汇报后端契约（T021）：
 * ① close 项目级幂等（已关闭仍 code===0）；② close 经 SSE {@code report:closed} 实时同步；③ 手动触发产汇报（兜底链路）。
 * 独立 H2 库；禁调度器（trigger 直连不受影响）；无真 workhorse（MockBrain 兜底未完成）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:dataweave-companion-us2-071;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "companion.patrol.enabled=false"
})
@DisplayName("Companion US2 巡检/汇报 契约（071）")
class CompanionUs2IT {

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
                .defaultHeader("X-Project-Id", "1")
                .responseTimeout(Duration.ofSeconds(20))
                .build();
        jdbc.update("DELETE FROM patrol_report");
        jdbc.update("DELETE FROM patrol_run");
    }

    @Test
    @DisplayName("POST /reports/{id}/close 幂等：首次与重复关闭均 code===0，汇报转 CLOSED")
    void close_isIdempotent() {
        long reportId = seedReport("DANGER", "3 任务失败");

        client.post().uri("/api/companion/reports/{id}/close", reportId).exchange()
                .expectStatus().isOk().expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.status").isEqualTo("CLOSED")
                .jsonPath("$.data.closedBy").isEqualTo("管理员");

        // 重复关闭仍成功（幂等）
        client.post().uri("/api/companion/reports/{id}/close", reportId).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.code").isEqualTo(0);

        Integer closed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM patrol_report WHERE id = ? AND status = 'CLOSED'", Integer.class, reportId);
        assertThat(closed).isEqualTo(1);
    }

    @Test
    @DisplayName("close 经 SSE report:closed 实时同步（项目级共享移除）")
    void close_propagatesViaSse() {
        long reportId = seedReport("WARN", "节点离线");

        Flux<String> body = client.get().uri("/api/companion/stream")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange().expectStatus().isOk()
                .returnResult(String.class).getResponseBody();

        // snapshot 后触发关闭，下一个事件应是 report:closed（data 负载含 "closed"）
        StepVerifier.create(body)
                .assertNext(s -> assertThat(s).contains("\"reports\""))   // snapshot 数据段
                .then(() -> client.post().uri("/api/companion/reports/{id}/close", reportId)
                        .exchange().expectStatus().isOk())
                .expectNextMatches(s -> s.contains("\"closed\""))          // report:closed
                .thenCancel()
                .verify(Duration.ofSeconds(15));
    }

    @Test
    @DisplayName("POST /routines/{id}/trigger 手动触发产汇报（兜底链路：run 终态 + 至少一条汇报）")
    void trigger_producesReport() {
        // 例程 id=1（data.sql seed TASK_FAILURE）
        Integer before = jdbc.queryForObject(
                "SELECT COUNT(*) FROM patrol_report WHERE tenant_id=1 AND project_id=1", Integer.class);

        client.post().uri("/api/companion/routines/{id}/trigger", 1).exchange()
                .expectStatus().isOk().expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.runId").isNumber();

        // 异步执行：轮询至汇报出现（mock brain → INFO 未完成兜底；真 workhorse 在线则正常汇报）
        long runId = -1;
        long t0 = System.currentTimeMillis();
        Integer after = before;
        while (System.currentTimeMillis() - t0 < 10_000) {
            after = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM patrol_report WHERE tenant_id=1 AND project_id=1", Integer.class);
            Integer runTerminal = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM patrol_run WHERE tenant_id=1 AND project_id=1 AND state IN ('SUCCEEDED','FAILED','TIMEOUT')",
                    Integer.class);
            if (after != null && after > 0 && runTerminal != null && runTerminal > 0) break;
            sleepQuiet(200);
        }
        assertThat(after).isGreaterThan(before == null ? 0 : before);
    }

    private long seedReport(String severity, String title) {
        jdbc.update("INSERT INTO patrol_report (tenant_id, project_id, domain, severity, title, summary, " +
                "detail_json, aggregate_count, status) VALUES (1, 1, 'TASK_FAILURE', ?, ?, '摘要', '{}', 1, 'UNREAD')",
                severity, title);
        Long id = jdbc.queryForObject(
                "SELECT MAX(id) FROM patrol_report WHERE tenant_id=1 AND project_id=1 AND title=?",
                Long.class, title);
        if (id == null) throw new IllegalStateException("seed 失败: " + title);
        return id;
    }

    private static void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
