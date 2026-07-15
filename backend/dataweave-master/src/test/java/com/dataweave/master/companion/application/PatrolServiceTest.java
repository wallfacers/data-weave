package com.dataweave.master.companion.application;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.sql.DataSource;

import com.dataweave.master.companion.domain.CompanionBrain;
import com.dataweave.master.companion.domain.PatrolResult;
import com.dataweave.master.companion.domain.PatrolRoutine;
import com.dataweave.master.companion.domain.PatrolRunStates;
import com.dataweave.master.companion.domain.ReportSeverities;
import com.dataweave.master.companion.domain.ReportStatuses;
import com.dataweave.master.companion.infrastructure.JdbcPatrolReportRepository;
import com.dataweave.master.companion.infrastructure.JdbcPatrolRoutineRepository;
import com.dataweave.master.companion.infrastructure.JdbcPatrolRunRepository;
import com.dataweave.master.domain.EventBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PatrolService 单测（T019）：成功落汇报 / 三条失败路径兜底"未完成" / FR-011 同领域聚合。
 * 用 scripted {@link CompanionBrain}（覆盖 selector.forPatrol）+ inline H2 + 真 repos，无需 workhorse。
 */
class PatrolServiceTest {

    private static final long TENANT = 1L;
    private static final long PROJECT = 1L;

    private DataSource ds;
    private JdbcTemplate jdbc;
    private JdbcPatrolRoutineRepository routineRepo;
    private JdbcPatrolRunRepository runRepo;
    private JdbcPatrolReportRepository reportRepo;
    private List<String> published;
    private PatrolService service;
    private long routineId;

    @BeforeEach
    void setUp() {
        ds = new SingleConnectionDataSource(
                "jdbc:h2:mem:companion_patrol_svc;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;" +
                "DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE", true);
        jdbc = new JdbcTemplate(ds);
        jdbc.execute(patrolRoutineDdl());
        jdbc.execute(patrolRunDdl());
        jdbc.execute(patrolReportDdl());
        routineRepo = new JdbcPatrolRoutineRepository(jdbc);
        runRepo = new JdbcPatrolRunRepository(jdbc);
        reportRepo = new JdbcPatrolReportRepository(jdbc);
        routineId = routineRepo.insert(TENANT, PROJECT, "TASK_FAILURE", true, "0 */15 * * * *", null, 120, 1L);
        published = new ArrayList<>();
        EventBus fakeBus = new EventBus() {
            @Override public void publish(String channel, String message) { published.add(message); }
            @Override public Subscription subscribe(String channel, Consumer<String> handler) { return () -> {}; }
        };
        CompanionEventPublisher publisher = new CompanionEventPublisher(fakeBus);
        CompanionStateResolver stateResolver = new CompanionStateResolver(reportRepo, runRepo,
                new CompanionTurnRegistry(), publisher);
        CompanionBriefingService briefingService = new CompanionBriefingService(runRepo, reportRepo, routineRepo, publisher);
        service = new PatrolService(runRepo, routineRepo, reportRepo, new CompanionBrainSelector(null, null) {
            @Override public com.dataweave.master.companion.domain.CompanionBrain forPatrol() { return scriptedBrain; }
        }, publisher, stateResolver, briefingService, 1);
    }

    private CompanionBrain scriptedBrain;

    @AfterEach
    void tearDown() {
        jdbc.execute("DROP ALL OBJECTS");
        ((SingleConnectionDataSource) ds).destroy();
    }

    @Test
    void success_landsReportAndSucceedsRun() {
        scriptedBrain = stubBrain(PatrolResult.ok(ReportSeverities.WARN, "2 任务失败", "ETL 失败", "{\"objects\":[]}"));
        long runId = newRunningRun();

        service.executeRun(runId);

        assertThat(runRepo.findById(runId).orElseThrow().state()).isEqualTo(PatrolRunStates.SUCCEEDED);
        assertThat(reportRepo.findOpenByProject(TENANT, PROJECT, 10)).hasSize(1);
        assertThat(reportRepo.existsOpenAnomaly(TENANT, PROJECT)).isTrue();
        assertThat(published).anyMatch(s -> s.contains("\"event\":\"report\"") && s.contains("\"created\""));
    }

    @Test
    void brainFailed_fallsBackToIncompleteReport() {
        scriptedBrain = stubBrain(PatrolResult.failed("workhorse 不可用"));
        long runId = newRunningRun();

        service.executeRun(runId);

        // 兜底：INFO 未完成汇报 + run FAILED，不静默（SC-007）
        assertThat(runRepo.findById(runId).orElseThrow().state()).isEqualTo(PatrolRunStates.FAILED);
        List<com.dataweave.master.companion.domain.PatrolReport> reports = reportRepo.findByProject(TENANT, PROJECT, null, 10);
        assertThat(reports).hasSize(1);
        assertThat(reports.get(0).severity()).isEqualTo(ReportSeverities.INFO);
        assertThat(reports.get(0).title()).contains("未完成");
        // INFO 不计异常 → alert 形态不触发
        assertThat(reportRepo.existsOpenAnomaly(TENANT, PROJECT)).isFalse();
    }

    @Test
    void brainThrows_fallsBackToIncompleteReport() {
        scriptedBrain = new CompanionBrain() {
            @Override public com.dataweave.master.companion.domain.ChatHandle openChat(long p, String c, com.dataweave.master.companion.domain.ChatCallbacks cb) { return null; }
            @Override public PatrolResult runPatrol(PatrolRoutine r, String s, int t) { throw new RuntimeException("boom"); }
            @Override public boolean healthy() { return true; }
            @Override public String name() { return "throwing"; }
        };
        long runId = newRunningRun();

        service.executeRun(runId);

        assertThat(runRepo.findById(runId).orElseThrow().state()).isEqualTo(PatrolRunStates.FAILED);
        assertThat(reportRepo.findByProject(TENANT, PROJECT, null, 10).get(0).severity()).isEqualTo(ReportSeverities.INFO);
    }

    @Test
    void aggregate_sameDomainAnomalyWithinWindowAggregates() {
        scriptedBrain = stubBrain(PatrolResult.ok(ReportSeverities.DANGER, "任务失败", "失败摘要", "{}"));
        service.executeRun(newRunningRun());   // 第一条 DANGER
        service.executeRun(newRunningRun());   // 第二条 DANGER（同领域 10 分钟窗口）→ 聚合

        // 只有一张卡片，aggregate_count=2；report:created 只发一次
        List<com.dataweave.master.companion.domain.PatrolReport> reports = reportRepo.findOpenByProject(TENANT, PROJECT, 10);
        assertThat(reports).hasSize(1);
        assertThat(reports.get(0).aggregateCount()).isEqualTo(2);
        assertThat(reports.get(0).status()).isEqualTo(ReportStatuses.UNREAD);
        long createdEvents = published.stream().filter(s -> s.contains("\"created\"")).count();
        assertThat(createdEvents).isEqualTo(1);
    }

    /** 建一条 CLAIMED→RUNNING 的 run，供 executeRun 处理。 */
    private long newRunningRun() {
        long runId = runRepo.tryClaimCreate(TENANT, PROJECT, routineId, "MANUAL",
                LocalDateTime.of(2026, 7, 15, 9, 0).plusNanos(System.nanoTime())).orElseThrow().id();
        runRepo.casStart(runId);
        return runId;
    }

    private static CompanionBrain stubBrain(PatrolResult result) {
        return new CompanionBrain() {
            @Override public com.dataweave.master.companion.domain.ChatHandle openChat(long p, String c, com.dataweave.master.companion.domain.ChatCallbacks cb) { return null; }
            @Override public PatrolResult runPatrol(PatrolRoutine r, String s, int t) { return result; }
            @Override public boolean healthy() { return true; }
            @Override public String name() { return "stub"; }
        };
    }

    private static String patrolRoutineDdl() {
        return """
                CREATE TABLE patrol_routine (
                    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    tenant_id BIGINT NOT NULL, project_id BIGINT NOT NULL,
                    domain VARCHAR(32) NOT NULL, enabled SMALLINT NOT NULL DEFAULT 1,
                    cron_expression VARCHAR(64) NOT NULL, scope_json TEXT,
                    timeout_seconds INTEGER NOT NULL DEFAULT 120,
                    created_by BIGINT, updated_by BIGINT,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    deleted SMALLINT NOT NULL DEFAULT 0, version INTEGER NOT NULL DEFAULT 0)
                """;
    }

    private static String patrolRunDdl() {
        return """
                CREATE TABLE patrol_run (
                    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    tenant_id BIGINT NOT NULL, project_id BIGINT NOT NULL,
                    routine_id BIGINT NOT NULL, trigger_type VARCHAR(16) NOT NULL,
                    scheduled_fire_time TIMESTAMP NOT NULL, state VARCHAR(16) NOT NULL DEFAULT 'CLAIMED',
                    started_at TIMESTAMP, finished_at TIMESTAMP, summary TEXT, error TEXT,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    version INTEGER NOT NULL DEFAULT 0)
                """;
    }

    private static String patrolReportDdl() {
        return """
                CREATE TABLE patrol_report (
                    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    tenant_id BIGINT NOT NULL, project_id BIGINT NOT NULL,
                    run_id BIGINT, domain VARCHAR(32) NOT NULL, severity VARCHAR(16) NOT NULL,
                    title VARCHAR(255) NOT NULL, summary TEXT, detail_json TEXT,
                    aggregate_count INTEGER NOT NULL DEFAULT 1, status VARCHAR(16) NOT NULL DEFAULT 'UNREAD',
                    closed_by VARCHAR(128), closed_at TIMESTAMP,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                """;
    }
}
