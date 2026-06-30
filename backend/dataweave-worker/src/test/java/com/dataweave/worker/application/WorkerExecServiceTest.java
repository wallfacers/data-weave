package com.dataweave.worker.application;

import com.dataweave.master.domain.lineage.StatementMetric;
import com.dataweave.worker.domain.ExecutionContext;
import com.dataweave.worker.domain.TaskExecutor;
import com.dataweave.worker.infrastructure.ShellTaskExecutor;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WorkerExecService 单元测试：幂等去重、真实执行、回调。
 */
class WorkerExecServiceTest {

    private WorkerExecService createService() {
        Map<String, TaskExecutor> executors = new HashMap<>();
        executors.put("SHELL", new ShellTaskExecutor());
        return new WorkerExecService(executors);
    }

    private ExecutionContext ctx(String content, String bizDate, int attempt, int timeout) {
        return new ExecutionContext(content, bizDate, attempt, timeout, null, "SHELL", null);
    }

    @Test
    void idempotencyKey_deterministic() {
        UUID id = UUID.randomUUID();
        assertThat(WorkerExecService.idempotencyKey(id, 1))
                .isEqualTo(WorkerExecService.idempotencyKey(id, 1))
                .isNotEqualTo(WorkerExecService.idempotencyKey(id, 2));
    }

    @Test
    void submitExecutesAndReportsFinished() throws Exception {
        WorkerExecService service = createService();
        UUID instanceId = UUID.randomUUID();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();

        service.submit(instanceId, 1, ctx("echo hello", "2026-06-12", 1, 10), null,
                new WorkerExecService.ReportCallback() {
                    @Override public void onStarted(UUID id) { }
                    @Override public void onFinished(UUID id, int exitCode, String tailLog, List<StatementMetric> statementMetrics) {
                        result.set("FINISHED:" + exitCode);
                        latch.countDown();
                    }
                    @Override public void onFailed(UUID id, String reason, String tailLog) {
                        result.set("FAILED:" + reason);
                        latch.countDown();
                    }
                });

        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(result.get()).isEqualTo("FINISHED:0");
    }

    @Test
    void duplicateSubmitIsIdempotent() throws Exception {
        WorkerExecService service = createService();
        UUID instanceId = UUID.randomUUID();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger finishCount = new AtomicInteger(0);

        WorkerExecService.ReportCallback cb = new WorkerExecService.ReportCallback() {
            @Override public void onStarted(UUID id) { }
            @Override public void onFinished(UUID id, int exitCode, String tailLog, List<StatementMetric> statementMetrics) {
                if (finishCount.incrementAndGet() == 1) {
                    latch.countDown();
                }
            }
            @Override public void onFailed(UUID id, String reason, String tailLog) {
                latch.countDown();
            }
        };

        // 第一次提交（接受），用 sleep 保证幂等键在第二次提交时仍在
        boolean accepted1 = service.submit(instanceId, 1, ctx("sleep 5 && echo ok", null, 1, 60), null, cb);
        assertThat(accepted1).isTrue();

        // 立即重复提交（应被幂等拒绝）
        boolean accepted2 = service.submit(instanceId, 1, ctx("echo ok", null, 1, 10), null, cb);
        assertThat(accepted2).isFalse();

        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        // 只执行一次
        assertThat(finishCount.get()).isEqualTo(1);
    }

    @Test
    void differentAttemptsAreBothAccepted() throws Exception {
        WorkerExecService service = createService();
        UUID instanceId = UUID.randomUUID();

        CountDownLatch latch = new CountDownLatch(2);
        AtomicInteger finishCount = new AtomicInteger(0);

        WorkerExecService.ReportCallback cb = new WorkerExecService.ReportCallback() {
            @Override public void onStarted(UUID id) { }
            @Override public void onFinished(UUID id, int exitCode, String tailLog, List<StatementMetric> statementMetrics) {
                finishCount.incrementAndGet();
                latch.countDown();
            }
            @Override public void onFailed(UUID id, String reason, String tailLog) {
                latch.countDown();
            }
        };

        boolean a1 = service.submit(instanceId, 1, ctx("echo attempt1", null, 1, 10), null, cb);
        boolean a2 = service.submit(instanceId, 2, ctx("echo attempt2", null, 2, 10), null, cb);
        assertThat(a1).isTrue();
        assertThat(a2).isTrue();

        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(finishCount.get()).isEqualTo(2);
    }

    @Test
    void failedTaskReportsFailure() throws Exception {
        WorkerExecService service = createService();
        UUID instanceId = UUID.randomUUID();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();

        service.submit(instanceId, 1, ctx("exit 1", null, 1, 10), null,
                new WorkerExecService.ReportCallback() {
                    @Override public void onStarted(UUID id) { }
                    @Override public void onFinished(UUID id, int exitCode, String tailLog, List<StatementMetric> statementMetrics) {
                        result.set("FINISHED");
                        latch.countDown();
                    }
                    @Override public void onFailed(UUID id, String reason, String tailLog) {
                        result.set("FAILED:" + reason);
                        latch.countDown();
                    }
                });

        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(result.get()).startsWith("FAILED:EXIT_CODE_1");
    }

    @Test
    void executeSync_returnsResult() {
        WorkerExecService service = createService();
        UUID instanceId = UUID.randomUUID();

        TaskExecutor.ExecutionResult result = service.executeSync(
                instanceId, 1, ctx("echo sync", "2026-06-12", 1, 10), null);

        assertThat(result).isNotNull();
        assertThat(result.success()).isTrue();
        assertThat(result.stdout()).contains("sync");
    }

    @Test
    void executeSync_idempotentReturnsNull() throws Exception {
        WorkerExecService service = createService();
        UUID instanceId = UUID.randomUUID();

        // 先提交异步（占住幂等键）
        CountDownLatch latch = new CountDownLatch(1);
        service.submit(instanceId, 1, ctx("sleep 10", null, 1, 60), null,
                new WorkerExecService.ReportCallback() {
                    @Override public void onStarted(UUID id) { }
                    @Override public void onFinished(UUID id, int exitCode, String tailLog, List<StatementMetric> statementMetrics) { latch.countDown(); }
                    @Override public void onFailed(UUID id, String reason, String tailLog) { latch.countDown(); }
                });

        // 同步执行同一幂等键 → null（幂等拒绝）
        TaskExecutor.ExecutionResult result = service.executeSync(instanceId, 1, ctx("echo x", null, 1, 10), null);
        assertThat(result).isNull();
    }
}
