package com.dataweave.api.infrastructure;

import com.dataweave.master.application.TaskExecutionGateway;
import com.dataweave.master.application.WorkerReportService;
import com.dataweave.master.domain.LogBus;
import com.dataweave.worker.domain.ExecutionContext;
import com.dataweave.worker.domain.TaskExecutor;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
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

    private final WorkerReportService reportService;
    private final LogBus logBus;
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
                                         Map<String, TaskExecutor> executors) {
        this.reportService = reportService;
        this.logBus = logBus;
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

            // all-in-one 零依赖模式：无对应执行器的类型（如 SQL，需外部数据源）模拟成功，闭合调度闭环。
            // 真实执行（如 SHELL）由对应执行器承载，分布式模式由 worker 进程按 type 选执行器执行。
            if (executor == null) {
                String msg = "[all-in-one] type=" + type + " 无内置执行器，模拟执行成功（接真实数据源走 distributed 模式）";
                lineConsumer.accept(msg);
                reportService.reportFinished(cmd.taskInstanceId(), 0, msg);
                return;
            }

            ExecutionContext ctx = new ExecutionContext(
                    cmd.content(),
                    cmd.bizDate(),
                    cmd.attempt(),
                    cmd.timeoutSeconds()
            );

            TaskExecutor.ExecutionResult result = executor.execute(ctx, lineConsumer);

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
