package com.dataweave.api.infrastructure;

import com.dataweave.master.application.TaskExecutionGateway;
import com.dataweave.master.application.WorkerReportService;
import com.dataweave.master.domain.Datasource;
import com.dataweave.master.domain.DatasourceRepository;
import com.dataweave.master.domain.LogBus;
import com.dataweave.worker.domain.ExecutionContext;
import com.dataweave.worker.domain.TaskExecutor;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
 */
@Component
@ConditionalOnProperty(name = "scheduler.mode", havingValue = "all-in-one", matchIfMissing = true)
public class InProcessTaskExecutionGateway implements TaskExecutionGateway {

    private static final Logger log = LoggerFactory.getLogger(InProcessTaskExecutionGateway.class);

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final WorkerReportService reportService;
    private final LogBus logBus;
    private final DatasourceRepository datasourceRepository;
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
                                         Map<String, TaskExecutor> executors) {
        this.reportService = reportService;
        this.logBus = logBus;
        this.datasourceRepository = datasourceRepository;
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
        try {
            reportService.reportStarted(cmd.taskInstanceId());

            // 逐行日志回调 → LogBus
            Consumer<String> lineConsumer = line -> logBus.append(cmd.taskInstanceId(), line);

            // 按任务 type 选执行器（默认 SHELL）
            String type = cmd.taskType() != null ? cmd.taskType().toUpperCase() : "SHELL";
            TaskExecutor executor = byType.get(type);

            // 数据源解析（SQL 用；解析不到则传 null → SqlTaskExecutor 方案 A 回退模拟）
            ExecutionContext.DataSourceRef dsRef = resolveDatasource(cmd.datasourceId());

            // DataWorks 风启动 banner（中文执行日志，沿用既有「内部审计日志」约定，非 i18n）
            emitStartBanner(lineConsumer, cmd, type, dsRef);

            // 防御：注册类型外的未知 type 无执行器时模拟成功，闭合调度闭环（SQL/ECHO/SHELL 已各有执行器）。
            if (executor == null) {
                String msg = "type=" + type + " 无内置执行器，模拟执行成功";
                lineConsumer.accept(msg);
                emitEndBanner(lineConsumer, true, 0, false);
                reportService.reportFinished(cmd.taskInstanceId(), 0, msg);
                return;
            }

            ExecutionContext ctx = new ExecutionContext(
                    cmd.content(),
                    cmd.bizDate(),
                    cmd.attempt(),
                    cmd.timeoutSeconds(),
                    cmd.runMode(),
                    type,
                    dsRef
            );

            TaskExecutor.ExecutionResult result = executor.execute(ctx, lineConsumer);

            emitEndBanner(lineConsumer, result.success(), result.exitCode(), result.timedOut());

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
        } catch (Exception e) {
            log.warn("[InProcExec] 实例 {} 执行异常：{}", cmd.taskInstanceId(), e.getMessage());
            reportService.reportFailed(cmd.taskInstanceId(), "EXEC_ERROR", "执行异常：" + e.getMessage());
        }
    }

    /** 解析任务绑定的业务数据源为连接引用；id 为空或行不存在/已删返回 null（SQL 执行端回退模拟）。 */
    private ExecutionContext.DataSourceRef resolveDatasource(Long datasourceId) {
        if (datasourceId == null) {
            return null;
        }
        Datasource ds = datasourceRepository.findById(datasourceId).orElse(null);
        if (ds == null || (ds.getDeleted() != null && ds.getDeleted() != 0)) {
            return null;
        }
        return new ExecutionContext.DataSourceRef(
                ds.getName(), ds.getTypeCode(), ds.getJdbcUrl(), ds.getUsername(), ds.getPasswordEnc());
    }

    /** DataWorks 风启动 banner：运行模式 / 类型 / 数据源 / 开始时间。 */
    private void emitStartBanner(Consumer<String> onLine, DispatchCommand cmd, String type,
                                 ExecutionContext.DataSourceRef ds) {
        onLine.accept("=========== DataWeave 任务运行 ===========");
        String dsLabel = ds != null ? (ds.name() + "（" + ds.typeCode() + "）") : "-";
        onLine.accept("运行模式: " + (cmd.runMode() != null ? cmd.runMode() : "NORMAL")
                + " | 类型: " + type + " | 数据源: " + dsLabel);
        onLine.accept("开始时间: " + LocalDateTime.now().format(TS));
        onLine.accept("=========================================");
    }

    /** DataWorks 风收尾 banner：状态 / 退出码 / 结束时间。 */
    private void emitEndBanner(Consumer<String> onLine, boolean success, int exitCode, boolean timedOut) {
        onLine.accept("=========== 执行结束 ===========");
        String status = timedOut ? "超时终止" : (success ? "成功" : "失败");
        onLine.accept("状态: " + status + " | 退出码: " + exitCode);
        onLine.accept("结束时间: " + LocalDateTime.now().format(TS));
        onLine.accept("===============================");
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
