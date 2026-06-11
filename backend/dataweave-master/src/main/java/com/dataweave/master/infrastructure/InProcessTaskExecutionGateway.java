package com.dataweave.master.infrastructure;

import com.dataweave.master.application.TaskExecutionGateway;
import com.dataweave.master.application.WorkerReportService;
import com.dataweave.master.domain.LogBus;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * {@link TaskExecutionGateway} 的进程内实现（all-in-one 默认）：异步执行任务并经 {@link WorkerReportService}
 * 回报，闭合「认领 → 下发 → 运行 → 回报 → 下游解锁」全链路。
 *
 * <p>当前为 mock 执行（标记 started → 写一行日志 → finished SUCCESS）；真实进程执行（stdout 采集、退出码、
 * 超时 kill）由 task 3.1 的 TaskExecutor 接入替换本类执行体。distributed 模式由 WebClient 网关替代（3.2）。
 */
@Component
@ConditionalOnProperty(name = "scheduler.mode", havingValue = "all-in-one", matchIfMissing = true)
public class InProcessTaskExecutionGateway implements TaskExecutionGateway {

    private static final Logger log = LoggerFactory.getLogger(InProcessTaskExecutionGateway.class);

    private final WorkerReportService reportService;
    private final LogBus logBus;
    private final ExecutorService pool = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors()),
            r -> {
                Thread t = new Thread(r, "inproc-worker");
                t.setDaemon(true);
                return t;
            });

    public InProcessTaskExecutionGateway(WorkerReportService reportService, LogBus logBus) {
        this.reportService = reportService;
        this.logBus = logBus;
    }

    @Override
    public void dispatch(DispatchCommand cmd) {
        pool.submit(() -> run(cmd));
    }

    private void run(DispatchCommand cmd) {
        try {
            reportService.reportStarted(cmd.taskInstanceId());
            String line = "[exec] 任务实例 " + cmd.taskInstanceId() + " 在 " + cmd.workerNodeCode()
                    + " 执行（attempt=" + cmd.attempt() + (cmd.bizDate() != null ? "，biz_date=" + cmd.bizDate() : "") + "）";
            logBus.append(cmd.taskInstanceId(), line);
            // mock：内容存在即视为成功（真实执行替换点见 3.1）
            String tail = line + "\n[exec] 执行成功（mock）";
            reportService.reportFinished(cmd.taskInstanceId(), 0, tail);
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
