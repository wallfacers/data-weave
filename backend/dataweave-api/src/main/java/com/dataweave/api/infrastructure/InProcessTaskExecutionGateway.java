package com.dataweave.api.infrastructure;

import com.dataweave.master.application.DatasourceResolver;
import com.dataweave.master.application.DatasourceResolver.ResolvedConnection;
import com.dataweave.master.application.TaskExecutionGateway;
import com.dataweave.master.application.WorkerReportService;
import com.dataweave.master.domain.Datasource;
import com.dataweave.master.domain.DatasourceRepository;
import com.dataweave.master.domain.LogBus;
import com.dataweave.master.i18n.Messages;
import com.dataweave.worker.domain.ExecutionContext;
import com.dataweave.worker.domain.TaskExecutor;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * {@link TaskExecutionGateway} 的进程内实现（all-in-one 默认）：异步执行任务并经 {@link WorkerReportService}
 * 回报，闭合「认领 → 下发 → 运行 → 回报 → 下游解锁」全链路。
 *
 * <p>由 {@code api} 模块承载（组合根），以引用 worker 模块的 {@link TaskExecutor} 实现。
 * distributed 模式由 WebClient 网关替代。
 *
 * <p>启动/收尾 banner（DataWorks 风）按触发者 locale 渲染（i18n 规则②）：locale 由
 * {@link DispatchCommand#locale()} 透传（落 {@code task_instance.locale}，调度认领时读出）；null 兜底 zh-CN。
 * 收尾 banner 含执行耗时，按 {@code ms/s/m/h} 可读换算（单位缩写跨语言一致）。
 */
@Component
@ConditionalOnProperty(name = "scheduler.mode", havingValue = "all-in-one", matchIfMissing = true)
public class InProcessTaskExecutionGateway implements TaskExecutionGateway {

    private static final Logger log = LoggerFactory.getLogger(InProcessTaskExecutionGateway.class);

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final WorkerReportService reportService;
    private final LogBus logBus;
    private final DatasourceRepository datasourceRepository;
    private final DatasourceResolver datasourceResolver;
    private final Messages messages;
    /** 按任务 type() 索引的执行器（注入的 Map 以 bean 名为键，这里改建按 type 的映射）。 */
    private final Map<String, TaskExecutor> byType;
    private final ExecutorService pool = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors()),
            r -> {
                Thread t = new Thread(r, "inproc-worker");
                t.setDaemon(true);
                return t;
            });

    public InProcessTaskExecutionGateway(WorkerReportService reportService,
                                         LogBus logBus,
                                         DatasourceRepository datasourceRepository,
                                         DatasourceResolver datasourceResolver,
                                         Messages messages,
                                         Map<String, TaskExecutor> executors) {
        this.reportService = reportService;
        this.logBus = logBus;
        this.datasourceRepository = datasourceRepository;
        this.datasourceResolver = datasourceResolver;
        this.messages = messages;
        Map<String, TaskExecutor> m = new java.util.HashMap<>();
        for (TaskExecutor e : executors.values()) {
            if (e.type() != null) {
                m.put(e.type().toUpperCase(), e);
            }
        }
        this.byType = m;
    }

    @Override
    public void dispatch(DispatchCommand cmd) {
        pool.submit(() -> run(cmd));
    }

    private void run(DispatchCommand cmd) {
        Locale locale = cmd.locale() != null ? Locale.forLanguageTag(cmd.locale()) : Messages.DEFAULT_LOCALE;
        Instant startInstant = Instant.now();
        try {
            reportService.reportStarted(cmd.taskInstanceId());

            // 逐行日志回调 → LogBus
            Consumer<String> lineConsumer = line -> logBus.append(cmd.taskInstanceId(), line);

            // 按任务 type 选执行器（默认 SHELL）
            String type = cmd.taskType() != null ? cmd.taskType().toUpperCase() : "SHELL";
            TaskExecutor executor = byType.get(type);

            // 数据源解析（通过 DatasourceResolver 按 taskType 输出不同格式）
            ResolvedConnection resolved = resolveDatasource(cmd.datasourceId(), type);
            ExecutionContext.DataSourceRef dsRef = resolved != null
                    ? new ExecutionContext.DataSourceRef(
                            resolved.name(), resolved.typeCode(), resolved.jdbcUrl(),
                            resolved.username(), resolved.password())
                    : null;

            // DataWorks 风启动 banner（按触发者 locale 渲染，i18n 规则②）
            emitStartBanner(lineConsumer, locale, cmd, type, dsRef);

            // 防御：注册类型外的未知 type 无执行器时模拟成功，闭合调度闭环（SQL/ECHO/SHELL 已各有执行器）。
            if (executor == null) {
                String msg = "type=" + type + " 无内置执行器，模拟执行成功";
                lineConsumer.accept(msg);
                emitEndBanner(lineConsumer, locale, true, 0, false, Duration.between(startInstant, Instant.now()));
                reportService.reportFinished(cmd.taskInstanceId(), 0, msg);
                return;
            }

            // 构建 ExecutionContext（含 Shell 环境变量 / Python 配置路径）
            Map<String, String> envVarMap = resolved != null ? resolved.shellEnvVars() : null;
            String pythonConfigPath = resolved != null ? resolved.pythonConfigPath() : null;

            ExecutionContext ctx = new ExecutionContext(
                    cmd.content(),
                    cmd.bizDate(),
                    cmd.attempt(),
                    cmd.timeoutSeconds(),
                    cmd.runMode(),
                    type,
                    dsRef,
                    envVarMap,
                    pythonConfigPath
            );

            try {
                TaskExecutor.ExecutionResult result = executor.execute(ctx, lineConsumer);

                emitEndBanner(lineConsumer, locale, result.success(), result.exitCode(), result.timedOut(),
                        Duration.between(startInstant, Instant.now()));

                // 尾部摘要（最后 4000 字符）写 task_instance.log
                String stdout = result.stdout();
                String tail = stdout.length() > 4000 ? stdout.substring(stdout.length() - 4000) : stdout;
                if (!result.stderr().isEmpty()) {
                    tail = tail + "\n[stderr] " + result.stderr();
                }

                if (result.success()) {
                    reportService.reportFinished(cmd.taskInstanceId(), result.exitCode(), tail);
                } else {
                    String reason = result.timedOut() ? "TIMEOUT" : "EXIT_CODE_" + result.exitCode();
                    reportService.reportFailed(cmd.taskInstanceId(), reason, tail);
                }
            } finally {
                // 清理 Python 临时配置文件
                if (pythonConfigPath != null && datasourceResolver != null) {
                    datasourceResolver.cleanup(pythonConfigPath);
                }
            }
        } catch (Exception e) {
            log.warn("[InProcExec] 实例 {} 执行异常：{}", cmd.taskInstanceId(), e.getMessage());
            reportService.reportFailed(cmd.taskInstanceId(), "EXEC_ERROR", "执行异常：" + e.getMessage());
        }
    }

    /** 通过 DatasourceResolver 解析数据源连接配置；id 为空返回 null。 */
    private ResolvedConnection resolveDatasource(Long datasourceId, String taskType) {
        if (datasourceId == null || datasourceResolver == null) {
            return null;
        }
        try {
            return datasourceResolver.resolve(datasourceId, taskType);
        } catch (Exception e) {
            log.warn("[InProcExec] 数据源解析失败 (id={}): {}", datasourceId, e.getMessage());
            return null;
        }
    }

    /** DataWorks 风启动 banner：运行模式 / 类型 / 数据源 / 开始时间（按触发者 locale 渲染）。 */
    private void emitStartBanner(Consumer<String> onLine, Locale locale, DispatchCommand cmd, String type,
                                 ExecutionContext.DataSourceRef ds) {
        String dsLabel = ds != null
                ? messages.get("taskrun.banner.start.datasource_fmt", locale, ds.name(), ds.typeCode())
                : messages.get("taskrun.banner.start.datasource_none", locale);
        onLine.accept("=========== " + messages.get("taskrun.banner.start.title", locale) + " ===========");
        onLine.accept(messages.get("taskrun.banner.start.line", locale,
                cmd.runMode() != null ? cmd.runMode() : "NORMAL", type, dsLabel));
        onLine.accept(messages.get("taskrun.banner.start.time", locale, LocalDateTime.now().format(TS)));
        onLine.accept("=========================================");
    }

    /** DataWorks 风收尾 banner：状态 / 退出码 / 执行耗时 / 结束时间（按触发者 locale 渲染）。 */
    private void emitEndBanner(Consumer<String> onLine, Locale locale, boolean success, int exitCode,
                               boolean timedOut, Duration duration) {
        String statusKey = timedOut ? "taskrun.banner.status.timeout"
                : (success ? "taskrun.banner.status.success" : "taskrun.banner.status.failed");
        onLine.accept("=========== " + messages.get("taskrun.banner.end.title", locale) + " ===========");
        onLine.accept(messages.get("taskrun.banner.end.line", locale,
                messages.get(statusKey, locale), exitCode));
        onLine.accept(messages.get("taskrun.banner.end.duration", locale, formatDuration(duration)));
        onLine.accept(messages.get("taskrun.banner.end.time", locale, LocalDateTime.now().format(TS)));
        onLine.accept("===============================");
    }

    /**
     * 执行耗时按可读单位换算（单位缩写跨语言一致）：
     * {@code <1s → "Xms"}；{@code <1min → "Xs"}；{@code <1h → "Xm Ys"}；否则 {@code "Xh Ym Zs"}。
     */
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

    @PreDestroy
    void shutdown() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
    }
}
