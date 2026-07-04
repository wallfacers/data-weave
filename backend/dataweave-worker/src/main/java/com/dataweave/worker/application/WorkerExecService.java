package com.dataweave.worker.application;

import com.dataweave.master.domain.lineage.StatementMetric;
import com.dataweave.master.i18n.Messages;
import com.dataweave.worker.domain.ExecutionContext;
import com.dataweave.worker.domain.TaskExecutor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
 *
 * <p><b>FR-007 按类型分发（contracts C4.1）</b>：构造建 {@code byType} 映射（同 InProcessTaskExecutionGateway），
 * {@code resolveExecutor(taskType)} 按 {@link ExecutionContext#taskType()} 取执行器；未知类型不静默当 SHELL、
 * 不伪装成功，返回 null → 调用方报 NO_EXECUTOR（可辨识）。数据源随 ctx 携带（C4.2 over-wire）。
 *
 * <p><b>SKIPPED（contracts C3 / FR-008）</b>：result.skipped()==true → onFinished（非失败完成、不阻塞下游），
 * tail 带 [SKIPPED] 标记；不新增状态机状态（FR-012）。
 */
@Component
public class WorkerExecService {

    private static final System.Logger log = System.getLogger(WorkerExecService.class.getName());

    private final Map<String, TaskExecutor> executors;
    /** 按任务 type() 索引的执行器（注入的 Map 以 bean 名为键，这里改建按 type 的映射，contracts C4.1）。 */
    private final Map<String, TaskExecutor> byType;
    private final Messages messages;
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

        /** 任务执行完成（含 SKIPPED——非失败完成，不阻塞下游）。statementMetrics = per-statement affected-rows（feature 025）。 */
        void onFinished(UUID taskInstanceId, int exitCode, String tailLog, List<StatementMetric> statementMetrics);

        /** 任务执行失败。 */
        void onFailed(UUID taskInstanceId, String reason, String tailLog);
    }

    public WorkerExecService(Map<String, TaskExecutor> executors, Messages messages) {
        this.executors = executors;
        this.messages = messages;
        Map<String, TaskExecutor> m = new HashMap<>();
        for (TaskExecutor e : executors.values()) {
            if (e.type() != null) {
                m.put(e.type().toUpperCase(), e);
            }
        }
        this.byType = m;
    }

    /**
     * 异步执行任务（幂等）。
     *
     * @param taskInstanceId 任务实例 ID
     * @param attempt        尝试序号（幂等钥匙之一）
     * @param ctx            执行上下文（content + taskType + datasource/env/spark，C4.2 over-wire）
     * @param lineConsumer   逐行输出回调（可为 null）
     * @param report         状态回报回调
     * @param locale         触发者 locale（banner 按此渲染；null 兜底 zh-CN）
     * @return true=接受执行（首次或幂等重复）；false=已在执行中（幂等拒绝）
     */
    public boolean submit(UUID taskInstanceId, int attempt, ExecutionContext ctx,
                          Consumer<String> lineConsumer, ReportCallback report, Locale locale) {
        String key = idempotencyKey(taskInstanceId, attempt);
        if (inFlight.putIfAbsent(key, true) != null) {
            // 幂等：同一 (instance, attempt) 已在执行
            log.log(System.Logger.Level.INFO, "幂等拒绝：instance={0}, attempt={1}", taskInstanceId, attempt);
            return false;
        }
        pool.submit(() -> {
            try {
                doRun(taskInstanceId, attempt, ctx, lineConsumer, report, locale);
            } finally {
                inFlight.remove(key);
            }
        });
        return true;
    }

    /**
     * 同步执行任务（幂等）。
     *
     * @return 执行结果，null 表示幂等拒绝
     */
    public TaskExecutor.ExecutionResult executeSync(UUID taskInstanceId, int attempt, ExecutionContext ctx,
                                                     Consumer<String> lineConsumer) {
        String key = idempotencyKey(taskInstanceId, attempt);
        if (inFlight.putIfAbsent(key, true) != null) {
            return null; // 幂等拒绝
        }
        try {
            TaskExecutor executor = resolveExecutor(ctx.taskType());
            if (executor == null) {
                return new TaskExecutor.ExecutionResult(false, -1, "", "", false, false,
                        "无 type=" + ctx.taskType() + " 的任务执行器");
            }
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

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private void doRun(UUID taskInstanceId, int attempt, ExecutionContext ctx,
                       Consumer<String> lineConsumer, ReportCallback report, Locale locale) {
        Locale loc = locale != null ? locale : Messages.DEFAULT_LOCALE;
        Instant startInstant = Instant.now();
        StringBuilder captured = new StringBuilder();
        Consumer<String> captureLine = line -> {
            captured.append(line).append('\n');
            if (lineConsumer != null) {
                lineConsumer.accept(line);
            }
        };

        try {
            report.onStarted(taskInstanceId);

            // DataWorks 风启动 banner
            emitStartBanner(captureLine, loc, ctx);

            TaskExecutor executor = resolveExecutor(ctx.taskType());
            if (executor == null) {
                String msg = "无 type=" + ctx.taskType() + " 的任务执行器（可用：" + byType.keySet() + "）";
                captureLine.accept(msg);
                emitEndBanner(captureLine, loc, false, -1, false, false,
                        Duration.between(startInstant, Instant.now()));
                report.onFailed(taskInstanceId, "NO_EXECUTOR", tail(captured));
                return;
            }

            TaskExecutor.ExecutionResult result = executor.execute(ctx, captureLine);

            // DataWorks 风收尾 banner（含执行耗时）
            emitEndBanner(captureLine, loc, result.success(), result.exitCode(), result.timedOut(),
                    result.skipped(), Duration.between(startInstant, Instant.now()));

            String tl = tail(captured);

            if (result.skipped()) {
                report.onFinished(taskInstanceId, result.exitCode(), "[SKIPPED] " + result.message(),
                        result.statementMetrics());
            } else if (result.success()) {
                report.onFinished(taskInstanceId, result.exitCode(), tl, result.statementMetrics());
            } else {
                String reason = result.timedOut() ? "TIMEOUT" : "EXIT_CODE_" + result.exitCode();
                report.onFailed(taskInstanceId, reason, tl);
            }
        } catch (Exception e) {
            log.log(System.Logger.Level.WARNING, "实例 {0} 执行异常：{1}", taskInstanceId, e.getMessage());
            emitEndBanner(captureLine, loc, false, -1, false, false,
                    Duration.between(startInstant, Instant.now()));
            report.onFailed(taskInstanceId, "EXEC_ERROR",
                    tail(captured) + "\n执行异常：" + e.getMessage());
        }
    }

    private static String tail(StringBuilder sb) {
        String s = sb.toString();
        return s.length() > 4000 ? s.substring(s.length() - 4000) : s;
    }

    /** DataWorks 风启动 banner（按触发者 locale 渲染）。 */
    private void emitStartBanner(Consumer<String> onLine, Locale locale, ExecutionContext ctx) {
        String type = ctx.taskType() != null ? ctx.taskType() : "SHELL";
        String runMode = ctx.runMode() != null ? ctx.runMode() : "NORMAL";
        String dsLabel = ctx.datasource() != null
                ? messages.get("taskrun.banner.start.datasource_fmt", locale,
                        ctx.datasource().name(), ctx.datasource().typeCode())
                : messages.get("taskrun.banner.start.datasource_none", locale);
        onLine.accept("=========== " + messages.get("taskrun.banner.start.title", locale) + " ===========");
        onLine.accept(messages.get("taskrun.banner.start.line", locale, runMode, type, dsLabel));
        onLine.accept(messages.get("taskrun.banner.start.time", locale, LocalDateTime.now().format(TS)));
        onLine.accept("=========================================");
    }

    /** DataWorks 风收尾 banner（按触发者 locale 渲染）。 */
    private void emitEndBanner(Consumer<String> onLine, Locale locale, boolean success, int exitCode,
                               boolean timedOut, boolean skipped, Duration duration) {
        String statusKey;
        if (skipped) {
            statusKey = "taskrun.banner.status.skipped";
        } else if (timedOut) {
            statusKey = "taskrun.banner.status.timeout";
        } else {
            statusKey = success ? "taskrun.banner.status.success" : "taskrun.banner.status.failed";
        }
        onLine.accept("=========== " + messages.get("taskrun.banner.end.title", locale) + " ===========");
        onLine.accept(messages.get("taskrun.banner.end.line", locale,
                messages.get(statusKey, locale), exitCode));
        onLine.accept(messages.get("taskrun.banner.end.duration", locale, formatDuration(duration)));
        onLine.accept(messages.get("taskrun.banner.end.time", locale, LocalDateTime.now().format(TS)));
        onLine.accept("===============================");
    }

    private static String formatDuration(Duration d) {
        long totalSec = d.toSeconds();
        if (totalSec < 1) {
            return d.toMillis() + "ms";
        }
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        if (h > 0) {
            return h + "h " + m + "m " + s + "s";
        }
        if (m > 0) {
            return m + "m " + s + "s";
        }
        return s + "s";
    }

    /** 按任务类型选执行器（contracts C4.1，镜像 InProcessTaskExecutionGateway.byType）；未知返回 null（可辨识失败）。 */
    private TaskExecutor resolveExecutor(String taskType) {
        String key = taskType != null && !taskType.isBlank() ? taskType.toUpperCase() : "SHELL";
        TaskExecutor executor = byType.get(key);
        if (executor == null) {
            log.log(System.Logger.Level.WARNING, "无 type={0} 的执行器（可用：{1}）",
                    new Object[]{key, byType.keySet()});
        }
        return executor;
    }

    static String idempotencyKey(UUID taskInstanceId, int attempt) {
        return taskInstanceId + ":" + attempt;
    }
}
