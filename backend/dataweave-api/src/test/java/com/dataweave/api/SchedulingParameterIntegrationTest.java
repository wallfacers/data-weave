package com.dataweave.api;

import com.dataweave.api.interfaces.TaskController;
import com.dataweave.master.application.ScheduleParamResolver;
import com.dataweave.master.application.WorkflowTriggerService;
import com.dataweave.master.domain.InstanceStates;
import com.dataweave.master.domain.LogBus;
import com.dataweave.master.domain.TaskInstance;
import com.dataweave.master.domain.TaskInstanceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * 调度参数占位符替换的端到端集成测试（H2 真库，all-in-one 进程内执行）：
 * <ul>
 *   <li>{@code placeholder_substitutedAndExecuted}：content 含 {@code ${yyyymmdd}} → 认领 → 替换 →
 *       SHELL 执行，task_instance.log 含替换后的具体值（tasks 4.1）。</li>
 *   <li>{@code unresolvedPlaceholder_failsWithoutBlockingOthers}：content 含未定义占位符 → 实例 FAILED +
 *       failure_reason 命名占位符，且同批次另一正常实例照常 SUCCESS（tasks 4.2，不连坐）。</li>
 * </ul>
 *
 * <p>{@code @TestPropertySource} 令本类拥有独立 context/H2 库，并把兜底轮询调到极长避免后台干扰
 * （认领由 {@code triggerTestRun} 的 wake 事件触发）。
 */
@SpringBootTest
@ActiveProfiles("h2")
@TestPropertySource(properties = {"scheduler.poll-interval-ms=3600000"})
class SchedulingParameterIntegrationTest {

    @Autowired
    WorkflowTriggerService triggerService;
    @Autowired
    TaskInstanceRepository taskInstanceRepository;
    @Autowired
    LogBus logBus;
    @Autowired
    TaskController taskController;
    @Autowired
    JdbcTemplate jdbc;

    private void ensureTestWorker() {
        jdbc.update("UPDATE worker_nodes SET status='OFFLINE'");
        jdbc.update("DELETE FROM worker_nodes WHERE node_code='node-sp'");
        jdbc.update("INSERT INTO worker_nodes (node_code, status, max_concurrent_tasks, reserved_test_slots, "
                + "load_avg, created_at, updated_at, deleted, version) "
                + "VALUES ('node-sp','ONLINE',2,2,0.0,?,?,0,0)", LocalDateTime.now(), LocalDateTime.now());
    }

    private Long seedTask(long id, String content, String paramsJson) {
        jdbc.update("INSERT INTO task_def (id, tenant_id, project_id, name, type, content, datasource_id, "
                + "target_datasource_id, params_json, timeout_sec, retry_max, status, current_version_no, "
                + "has_draft_change, created_by, updated_by, created_at, updated_at, deleted, version) "
                + "VALUES (?,1,1,'sp-test','SHELL',?,NULL,NULL,?,10,0,'DRAFT',0,1,1,1,?,?,0,0)",
                id, content, paramsJson, LocalDateTime.now(), LocalDateTime.now());
        return id;
    }

    @Test
    void placeholder_substitutedAndExecuted() throws Exception {
        ensureTestWorker();
        Long taskId = seedTask(900100L, "echo dt=${yyyymmdd}", null);
        UUID instId = triggerService.triggerTestRun(taskId, "2026-06-11", java.util.Locale.SIMPLIFIED_CHINESE);

        boolean done = await(Duration.ofSeconds(20), () -> isTerminal(instId));
        assertThat(done).as("实例应在超时内到达终态").isTrue();

        TaskInstance ti = taskInstanceRepository.findById(instId).orElseThrow();
        assertThat(ti.getState()).isEqualTo(InstanceStates.SUCCESS);
        assertThat(ti.getLog()).asString().contains("dt=20260611");  // ${yyyymmdd} 基于 biz_date 2026-06-11
    }

    @Test
    void unresolvedPlaceholder_failsWithoutBlockingOthers() throws Exception {
        ensureTestWorker();
        Long badTask = seedTask(900200L, "echo ${nope}", null);
        Long goodTask = seedTask(900201L, "echo ok", null);

        UUID badInst = triggerService.triggerTestRun(badTask, "2026-06-11", java.util.Locale.SIMPLIFIED_CHINESE);
        UUID goodInst = triggerService.triggerTestRun(goodTask, "2026-06-11", java.util.Locale.SIMPLIFIED_CHINESE);

        boolean done = await(Duration.ofSeconds(20), () -> isTerminal(badInst) && isTerminal(goodInst));
        assertThat(done).as("两个实例都应在超时内到达终态").isTrue();

        TaskInstance bad = taskInstanceRepository.findById(badInst).orElseThrow();
        assertThat(bad.getState()).isEqualTo(InstanceStates.FAILED);
        assertThat(bad.getFailureReason()).contains("nope");

        // 坏实例被隔离（resolveContentSafely catch 不抛穿认领事务），不连坐正常实例
        TaskInstance good = taskInstanceRepository.findById(goodInst).orElseThrow();
        assertThat(good.getState()).isEqualTo(InstanceStates.SUCCESS);
    }

    @Test
    void banner_renderedByTriggerLocale() throws Exception {
        // task-run-decouple design.md:60 / tasks.md 2.6：banner 按触发者 locale 渲染（i18n 规则②）。
        // 端到端验证 locale 全链路：triggerService → task_instance.locale → scheduler 认领读出 →
        // DispatchCommand.locale → InProcessTaskExecutionGateway 按 locale 渲染 banner。
        // 注意：banner 经 logBus 实时流输出（task_instance.log 字段只存 executor stdout tail，不含 banner）。
        ensureTestWorker();

        // 英文 locale 触发 → banner 渲染英文（半角括号 / 冒号后带空格）
        Long enTask = seedTask(900300L, "echo hello-en", null);
        UUID enInst = triggerService.triggerTestRun(enTask, "2026-06-11", java.util.Locale.US);
        assertThat(await(Duration.ofSeconds(20), () -> isTerminal(enInst)))
                .as("英文 locale 实例应在超时内到达终态").isTrue();
        String enBanner = logBus.read(enInst, null, 200).stream()
                .map(LogBus.Entry::line).reduce("", (a, b) -> a + "\n" + b);
        assertThat(enBanner).contains("DataWeave Task Run", "Run mode:", "Execution Finished", "Datasource: -", "Duration:");
        assertThat(enBanner).doesNotContain("任务运行");  // 未误走中文兜底

        // 中文 locale 触发 → banner 渲染中文
        Long zhTask = seedTask(900301L, "echo hello-zh", null);
        UUID zhInst = triggerService.triggerTestRun(zhTask, "2026-06-11", java.util.Locale.SIMPLIFIED_CHINESE);
        assertThat(await(Duration.ofSeconds(20), () -> isTerminal(zhInst)))
                .as("中文 locale 实例应在超时内到达终态").isTrue();
        String zhBanner = logBus.read(zhInst, null, 200).stream()
                .map(LogBus.Entry::line).reduce("", (a, b) -> a + "\n" + b);
        assertThat(zhBanner).contains("DataWeave 任务运行", "运行模式:", "执行结束", "执行耗时:");
    }

    @Test
    void previewEndpoint_resolvesPlaceholders() {
        var req = new TaskController.PreviewRequest(
                "echo dt=${yyyymmdd} and ${dt}", "2026-06-11", "{\"dt\":\"${yyyymmdd-1}\"}");
        var res = taskController.previewParams(req);
        assertThat(res.code()).isZero();
        assertThat(res.data().get("content")).isEqualTo("echo dt=20260611 and 20260610");
    }

    @Test
    void previewEndpoint_reportsUnresolvedAsError() {
        // i18n 后契约：previewParams 上抛 UnresolvedPlaceholderException（BizException，携带 i18n code +
        // 占位符名 args），由 GlobalExceptionHandler 按请求 locale 翻成 400 文案（HTTP 本地化路径已由
        // GlobalExceptionHandlerI18nTest 覆盖）。此处验证异常携带稳定 code 与占位符名供本地化插值。
        var req = new TaskController.PreviewRequest("echo ${nope}", "2026-06-11", null);
        assertThatExceptionOfType(ScheduleParamResolver.UnresolvedPlaceholderException.class)
                .isThrownBy(() -> taskController.previewParams(req))
                .satisfies(e -> {
                    assertThat(e.getCode()).isEqualTo("schedule.placeholder.undefined");
                    assertThat(e.getArgs()).contains("nope");
                });
    }

    private boolean isTerminal(UUID id) {
        String s = taskInstanceRepository.findById(id).map(TaskInstance::getState).orElse(null);
        return InstanceStates.SUCCESS.equals(s) || InstanceStates.FAILED.equals(s) || "STOPPED".equals(s);
    }

    private boolean await(Duration timeout, java.util.function.BooleanSupplier cond) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (cond.getAsBoolean()) {
                return true;
            }
            Thread.sleep(100);
        }
        return cond.getAsBoolean();
    }
}
