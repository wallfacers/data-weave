package com.dataweave.api;

import com.dataweave.api.infrastructure.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 036 FR-016：数据新鲜度按 (tenantId, projectId) 隔离（全栈 H2 + JWT）。
 *
 * <p>造双项目任务（tenant=1，project=1/2）各带成功实例，断言
 * {@code /api/freshness?projectId=N} 只返回当前项目的任务，跨项目 0 串。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
class FreshnessProjectIsolationTest {

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    JwtUtil jwtUtil;

    WebTestClient client;

    static UUID instUuid(int seq) {
        return UUID.fromString(String.format("00000000-0000-7000-8000-%012d", seq));
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM task_instance WHERE task_id BETWEEN 880200 AND 880299");
        jdbc.update("DELETE FROM task_def WHERE id BETWEEN 880200 AND 880299");
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", JwtTestSupport.bearer(jwtUtil))
                .build();
    }

    void insertTask(long id, long projectId, String name) {
        jdbc.update("INSERT INTO task_def (id, tenant_id, project_id, name, type, content, status, "
                        + "frozen, owner_id, datasource_id, created_at, updated_at, deleted, version) "
                        + "VALUES (?, 1, ?, ?, 'SQL', 'SELECT 1', 'ONLINE', 0, 1, 1, "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0)",
                id, projectId, name);
    }

    void insertSuccess(UUID id, long taskId, long projectId, LocalDateTime finished) {
        jdbc.update("INSERT INTO task_instance (id, tenant_id, project_id, task_id, run_mode, state, "
                        + "biz_date, started_at, finished_at, created_at, updated_at, deleted, version) "
                        + "VALUES (?, 1, ?, ?, 'NORMAL', 'SUCCESS', '2026-06-30', ?, ?, "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0)",
                id, projectId, taskId, finished, finished);
    }

    @Test
    void freshness_项目隔离_只返回当前项目任务() {
        LocalDateTime now = LocalDateTime.now();
        insertTask(880201L, 1, "zzisofresh_p1");
        insertTask(880202L, 2, "zzisofresh_p2");
        insertSuccess(instUuid(880201), 880201L, 1, now.minusHours(1));
        insertSuccess(instUuid(880202), 880202L, 2, now.minusHours(1));

        // 项目 1：只见 zzisofresh_p1
        client.get().uri(b -> b.path("/api/freshness")
                        .queryParam("projectId", "1").queryParam("taskName", "zziso")
                        .queryParam("page", "0").queryParam("size", "20").build())
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.items.length()").isEqualTo(1)
                .jsonPath("$.data.items[0].name").isEqualTo("zzisofresh_p1");

        // 项目 2：只见 zzisofresh_p2（项目 1 的任务不泄漏）
        client.get().uri(b -> b.path("/api/freshness")
                        .queryParam("projectId", "2").queryParam("taskName", "zziso")
                        .queryParam("page", "0").queryParam("size", "20").build())
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.items.length()").isEqualTo(1)
                .jsonPath("$.data.items[0].name").isEqualTo("zzisofresh_p2");
    }

    @Test
    void freshness_项目隔离_他项目任务计入总数不泄漏() {
        LocalDateTime now = LocalDateTime.now();
        insertTask(880211L, 1, "zzisocount_p1a");
        insertTask(880212L, 1, "zzisocount_p1b");
        insertTask(880213L, 2, "zzisocount_p2");  // 项目 2，不应出现在项目 1 结果
        insertSuccess(instUuid(880211), 880211L, 1, now.minusHours(1));
        insertSuccess(instUuid(880212), 880212L, 1, now.minusHours(2));
        insertSuccess(instUuid(880213), 880213L, 2, now.minusHours(1));

        client.get().uri(b -> b.path("/api/freshness")
                        .queryParam("projectId", "1").queryParam("taskName", "zzisocount")
                        .queryParam("page", "0").queryParam("size", "20").build())
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.total").isEqualTo(2)
                .jsonPath("$.data.items.length()").isEqualTo(2);
    }
}
