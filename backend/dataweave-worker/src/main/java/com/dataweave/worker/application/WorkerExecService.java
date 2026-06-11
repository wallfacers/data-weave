package com.dataweave.worker.application;

import com.dataweave.worker.domain.ExecutionContext;
import com.dataweave.worker.domain.TaskExecutor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Worker 侧任务执行服务（task 3.2）：幂等执行 + 进程管理。
 *
 * <p>幂等键：{@code (taskInstanceId, attempt)} —— 同一请求重复到达不重复执行。
 * distributed 模式下 master 可能重试下发（网络超时）或多 master 竞态，worker 必须去重。
 *
 * <p>all-in-one 模式下由 {@code InProcessTaskExecutionGateway} 直接调 TaskExecutor，
 * 不经过本服务；distributed 模式下由 worker HTTP exec 端点调本服务。
 */
@Component
public class WorkerExecService {

    private static final System.Logger log = System.getLogger(WorkerExecService.class.getName());

    private final Map<String, TaskExecutor> executors;
    /** 幂等键 → 执行状态（正在执行=true） */
    private final ConcurrentHashMap<String, Boolean> inFlight = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "worker-exec");
        t.setDaemon(true);
        return t;
    });

    /** 执行结果回调接口。 */
    public interface ReportCallback {
        /** 任务开始执行。 */
        void onStarted(UUID taskInstanceId);

        /** 任务执行完成。 */
        void onFinished(UUID taskInstanceId, int exitCode, String tailLog);

        /** 任务执行失败。 */
        void onFailed(UUID taskInstanceId, String reason, String tailLog);
    }

    public WorkerExecService(Map<String, TaskExecutor> executors) {
        this.executors = executors;
    }

    /**
     * 异步执行任务（幂等）。
     *
     * @param taskInstanceId 任务实例 ID
     * @param attempt        尝试序号
     * @param bizDate        业务日期
     * @param content        执行内容
     * @param timeoutSeconds 超时秒数
     * @param lineConsumer   逐行输出回调（可为 null）
     * @param report         状态回报回调
     * @return true=接受执行（首次或幂等重复）；false=已在执行中（幂等拒绝）
     */
    public boolean submit(UUID taskInstanceId, int attempt, String bizDate,
                          String content, int timeoutSeconds,
                          Consumer<String> lineConsumer, ReportCallback report) {
        String key = idempotencyKey(taskInstanceId, attempt);
        if (inFlight.putIfAbsent(key, true) != null) {
            // 幂等：同一 (instance, attempt) 已在执行
            log.log(System.Logger.Level.INFO, "幂等拒绝：instance={0}, attempt={1}", taskInstanceId, attempt);
            return false;
        }
        pool.submit(() -> {
            try {
                doRun(taskInstanceId, attempt, bizDate, content, timeoutSeconds, lineConsumer, report);
            } finally {
                inFlight.remove(key);
            }
        });
        return true;
    }

    /**
     * 同步执行任务（幂等），用于 all-in-one 模式。
     *
     * @return 执行结果，null 表示幂等拒绝
     */
    public TaskExecutor.ExecutionResult executeSync(UUID taskInstanceId, int attempt, String bizDate,
                                                     String content, int timeoutSeconds,
                                                     Consumer<String> lineConsumer) {
        String key = idempotencyKey(taskInstanceId, attempt);
        if (inFlight.putIfAbsent(key, true) != null) {
            return null; // 幂等拒绝
        }
        try {
            TaskExecutor executor = resolveExecutor();
            if (executor == null) {
                return new TaskExecutor.ExecutionResult(false, -1, "", "", false, false, "无可用的任务执行器");
            }
            ExecutionContext ctx = new ExecutionContext(content, bizDate, attempt, timeoutSeconds);
            return executor.execute(ctx, lineConsumer);
        } finally {
            inFlight.remove(key);
        }
    }

    /** 当前正在执行的任务数。 */
    public int inFlightCount() {
        return inFlight.size();
    }

    /** 优雅停机：拒绝新任务，等待运行中任务完成。 */
    public void shutdown(long awaitSeconds) {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(awaitSeconds, java.util.concurrent.TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
    }

    private void doRun(UUID taskInstanceId, int attempt, String bizDate,
                       String content, int timeoutSeconds,
                       Consumer<String> lineConsumer, ReportCallback report) {
        try {
            report.onStarted(taskInstanceId);

            TaskExecutor executor = resolveExecutor();
            if (executor == null) {
                report.onFailed(taskInstanceId, "NO_EXECUTOR", "无可用的任务执行器");
                return;
            }

            ExecutionContext ctx = new ExecutionContext(content, bizDate, attempt, timeoutSeconds);
            TaskExecutor.ExecutionResult result = executor.execute(ctx, lineConsumer);

            String stdout = result.stdout();
            String tail = stdout.length() > 4000 ? stdout.substring(stdout.length() - 4000) : stdout;

            if (result.success()) {
                report.onFinished(taskInstanceId, result.exitCode(), tail);
            } else {
                String reason = result.timedOut() ? "TIMEOUT" : "EXIT_CODE_" + result.exitCode();
                report.onFailed(taskInstanceId, reason, tail);
            }
        } catch (Exception e) {
            log.log(System.Logger.Level.WARNING, "实例 {0} 执行异常：{1}", taskInstanceId, e.getMessage());
            report.onFailed(taskInstanceId, "EXEC_ERROR", "执行异常：" + e.getMessage());
        }
    }

    private TaskExecutor resolveExecutor() {
        TaskExecutor executor = executors.get("SHELL");
        if (executor != null) {
            return executor;
        }
        // 降级：取第一个
        return executors.values().stream().findFirst().orElse(null);
    }

    static String idempotencyKey(UUID taskInstanceId, int attempt) {
        return taskInstanceId + ":" + attempt;
    }
}
