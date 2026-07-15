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
 * 071 US1 管家 SSE 直播流契约（T016）。首个加载真实 schema.sql+data.sql 的 IT——完整验证 T003 DDL/seed 在 H2 跑通。
 * 验证：建连先发 snapshot（state+briefing+reports 三段结构）、异常种子驱动 state=alert、干净态回落 idle。
 * 独立 H2 库防串台；无真 workhorse（stream 不依赖大脑）。SSE 经 JwtAuthFilter 的 ?token 鉴权（这里走 Bearer 头）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:dataweave-companion-us1-071;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
})
@DisplayName("Companion US1 SSE snapshot/状态 契约（071）")
class CompanionStreamIT {

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
        // 干净基线：project=1 不留汇报/巡检执行（data.sql 未 seed 这两表，双保险）
        jdbc.update("DELETE FROM patrol_report");
        jdbc.update("DELETE FROM patrol_run");
    }

    @Test
    @DisplayName("GET /stream 建连先发 snapshot（state+briefing+reports 三段结构）")
    void streamEmitsSnapshotStructure() {
        Flux<String> body = client.get().uri("/api/companion/stream")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange().expectStatus().isOk()
                .returnResult(String.class).getResponseBody();

        StepVerifier.create(body)
                .assertNext(s -> {
                    assertThat(s).contains("\"state\"");
                    assertThat(s).contains("\"briefing\"").contains("todayRuns").contains("openAnomalies");
                    assertThat(s).contains("\"reports\"");
                })
                .thenCancel()
                .verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("干净态 snapshot.state=idle；种子 DANGER 异常后 snapshot.state=alert")
    void anomalyDrivesAlertState() {
        // 干净态 → idle（无 RUNNING run、无未关闭异常、无活跃 turn）
        Flux<String> idle = client.get().uri("/api/companion/stream")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange().expectStatus().isOk()
                .returnResult(String.class).getResponseBody();
        StepVerifier.create(idle)
                .assertNext(s -> assertThat(s).contains("\"state\":\"idle\""))
                .thenCancel().verify(Duration.ofSeconds(10));

        // 种一条未关闭 DANGER 异常
        jdbc.update("INSERT INTO patrol_report (tenant_id, project_id, domain, severity, title, summary, " +
                "detail_json, aggregate_count, status) VALUES (1, 1, 'TASK_FAILURE', 'DANGER', '3 任务失败', " +
                "'ETL 链路三连失败', '{}', 3, 'UNREAD')");

        // → alert
        Flux<String> alert = client.get().uri("/api/companion/stream")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange().expectStatus().isOk()
                .returnResult(String.class).getResponseBody();
        StepVerifier.create(alert)
                .assertNext(s -> {
                    assertThat(s).contains("\"state\":\"alert\"");
                    assertThat(s).contains("3 任务失败");   // snapshot.reports 含该汇报
                })
                .thenCancel().verify(Duration.ofSeconds(10));
    }
}
