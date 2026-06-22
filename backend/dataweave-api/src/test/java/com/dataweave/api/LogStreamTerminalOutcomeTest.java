package com.dataweave.api;

import com.dataweave.api.interfaces.OpsController;
import com.dataweave.master.domain.LogBus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GET /api/ops/instances/{id}/logs/stream 终态契约回归（run-tab-status-dot）：
 * <ul>
 *   <li>连接时实例已终态：{@code end} 事件携带 {@code {"state": <终态>}}（SUCCESS/FAILED/STOPPED 各一）；</li>
 *   <li>live 路径：实例进入终态时 emit 带 outcome 的 {@code end} 并关流；</li>
 *   <li>live 路径：实例运行中（有/无日志）时不 emit {@code end}、不关流。</li>
 * </ul>
 *
 * <p>直调 {@link OpsController#logStream} 拿 {@code Flux}（绕开 HTTP/安全层，免 JWT）。
 * 全部用真实时间 + 有界 {@code block(Duration)} / {@code future.get(timeout)}，不使用虚拟时间
 * （{@code concatMap} 内有阻塞 JDBC 查询，与虚拟时间调度器混用易死锁），且每个用例都有硬超时，杜绝悬挂。
 */
@SpringBootTest
@ActiveProfiles("h2")
class LogStreamTerminalOutcomeTest {

    private static final Duration BOUND = Duration.ofSeconds(15);

    @Autowired OpsController opsController;
    @Autowired JdbcTemplate jdbc;
    @Autowired LogBus logBus;

    /** 建一个 run_mode=NORMAL、指定 state 的 task_instance（含其所属 workflow_instance）。 */
    private UUID insertInstance(String state) {
        UUID id = UUID.randomUUID();
        UUID wfId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update(
                "INSERT INTO workflow_instance (id, tenant_id, project_id, workflow_id, state, biz_date, "
                        + "started_at, finished_at, created_at, updated_at, deleted, version) "
                        + "VALUES (?, 1, 1, 1, ?, '2026-06-22', ?, NULL, ?, ?, 0, 0)",
                wfId, state, now, now, now);
        jdbc.update(
                "INSERT INTO task_instance (id, tenant_id, project_id, workflow_instance_id, task_id, "
                        + "run_mode, state, attempt, created_at, updated_at, deleted, version) "
                        + "VALUES (?, 1, 1, ?, 1, 'NORMAL', ?, 0, ?, ?, 0, 0)",
                id, wfId, state, now, now);
        return id;
    }

    private static boolean isEnd(ServerSentEvent<?> sse) {
        return "end".equals(sse.event());
    }

    // ─── 连接时已终态：end 携带 state（streamEndedLogs 路径）──────────────────

    @Test
    void 连接时已终态_success_携带state() {
        UUID id = insertInstance("SUCCESS");
        List<ServerSentEvent<String>> events = opsController.logStream(id, null).collectList().block(BOUND);
        assertThat(events).isNotNull().isNotEmpty();
        ServerSentEvent<String> last = events.get(events.size() - 1);
        assertThat(last.event()).isEqualTo("end");
        assertThat(last.data()).contains("\"state\":\"SUCCESS\"");
    }

    @Test
    void 连接时已终态_failed_携带state() {
        UUID id = insertInstance("FAILED");
        List<ServerSentEvent<String>> events = opsController.logStream(id, null).collectList().block(BOUND);
        assertThat(events).isNotNull();
        ServerSentEvent<String> last = events.get(events.size() - 1);
        assertThat(last.event()).isEqualTo("end");
        assertThat(last.data()).contains("\"state\":\"FAILED\"");
    }

    @Test
    void 连接时已终态_stopped_携带state() {
        UUID id = insertInstance("STOPPED");
        List<ServerSentEvent<String>> events = opsController.logStream(id, null).collectList().block(BOUND);
        assertThat(events).isNotNull();
        ServerSentEvent<String> last = events.get(events.size() - 1);
        assertThat(last.event()).isEqualTo("end");
        assertThat(last.data()).contains("\"state\":\"STOPPED\"");
    }

    // ─── live 路径（连接时实例 RUNNING）──────────────────────────────────────

    @Test
    void live路径_运行中不emitEnd不关流() throws InterruptedException {
        UUID id = insertInstance("RUNNING");
        // 采集 800ms（≈4 个 tick；tick0 查 state=RUNNING → 不 emit end），断言无 end 事件。
        List<ServerSentEvent<String>> events = opsController.logStream(id, null)
                .take(Duration.ofMillis(800)).collectList().block(BOUND);
        assertThat(events).isNotNull();
        assertThat(events).noneMatch(LogStreamTerminalOutcomeTest::isEnd);
    }

    @Test
    void live路径_运行中有日志无end() {
        UUID id = insertInstance("RUNNING");
        logBus.append(id, "line-1");
        logBus.append(id, "line-2");
        List<ServerSentEvent<String>> events = opsController.logStream(id, null)
                .take(Duration.ofMillis(800)).collectList().block(BOUND);
        assertThat(events).isNotNull();
        assertThat(events).extracting(ServerSentEvent::data).contains("line-1", "line-2");
        assertThat(events).noneMatch(LogStreamTerminalOutcomeTest::isEnd);
    }

    @Test
    void live路径_终态时emit带outcome的end并关流() throws Exception {
        UUID id = insertInstance("RUNNING");
        // 装配时 state=RUNNING → 走 live 路径；订阅后让流跑一会再翻 state，
        // 下一个状态检查 tick（≤2s）见到 SUCCESS → emit end → takeUntil 完成 → future 完成。
        Flux<ServerSentEvent<String>> flux = opsController.logStream(id, null);
        CompletableFuture<List<ServerSentEvent<String>>> future = flux.collectList().toFuture();
        Thread.sleep(400); // 确保 live 路径已激活（已发若干 tick）
        jdbc.update("UPDATE task_instance SET state='SUCCESS', updated_at=? WHERE id=?", LocalDateTime.now(), id);
        List<ServerSentEvent<String>> events = future.get(BOUND.toSeconds(), TimeUnit.SECONDS);
        assertThat(events).isNotNull();
        assertThat(events).anyMatch(s -> isEnd(s) && s.data() != null && s.data().contains("\"state\":\"SUCCESS\""));
    }
}
