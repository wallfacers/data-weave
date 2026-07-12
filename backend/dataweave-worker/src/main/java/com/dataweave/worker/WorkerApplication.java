package com.dataweave.worker;

import com.dataweave.master.infrastructure.TimezoneBootstrap;
import com.dataweave.worker.application.IncarnationManager;
import com.dataweave.worker.application.WorkerExecService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;

import jakarta.annotation.PreDestroy;

/**
 * Worker 独立进程入口（task 3.7）。
 *
 * <p>以 {@code scheduler.mode=distributed} 独立启动 worker 进程：
 * <pre>
 * java -jar dataweave-worker.jar --dataweave.worker.node-code=worker-1
 * </pre>
 *
 * <p>SIGTERM 优雅停机流程：
 * <ol>
 *   <li>停止接收新任务</li>
 *   <li>等待运行中任务完成（可配 drain 超时）</li>
 *   <li>超时未完成的任务按 worker 重启语义处理</li>
 *   <li>进程退出</li>
 * </ol>
 */
@SpringBootApplication(excludeName = {
    "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
    "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration",
    "org.springframework.boot.autoconfigure.data.jdbc.JdbcRepositoriesAutoConfiguration"
})
@EnableScheduling
public class WorkerApplication {

    private static final Logger log = LoggerFactory.getLogger(WorkerApplication.class);

    private final WorkerExecService execService;
    private final IncarnationManager incarnationManager;
    private final long drainTimeoutSeconds;

    private volatile boolean accepting = true;

    public WorkerApplication(WorkerExecService execService,
                             IncarnationManager incarnationManager,
                             @Value("${dataweave.worker.drain-timeout-seconds:60}") long drainTimeoutSeconds) {
        this.execService = execService;
        this.incarnationManager = incarnationManager;
        this.drainTimeoutSeconds = drainTimeoutSeconds;
    }

    public static void main(String[] args) {
        TimezoneBootstrap.init();
        SpringApplication.run(WorkerApplication.class, args);
    }

    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        log.info("[Worker] 进程启动完成，incarnation={}，等待任务下发...", incarnationManager.incarnation());
        accepting = true;
    }

    /**
     * SIGTERM 优雅停机：拒新任务 → drain 运行中 → 超时强退。
     */
    @PreDestroy
    void onShutdown() {
        log.info("[Worker] 收到停机信号，停止接收新任务，等待运行中任务完成（drain 超时 {}s）...",
                drainTimeoutSeconds);
        accepting = false;

        long start = System.currentTimeMillis();
        long deadline = start + drainTimeoutSeconds * 1000;

        // 等待运行中任务完成
        while (execService.inFlightCount() > 0 && System.currentTimeMillis() < deadline) {
            log.info("[Worker] drain 中，剩余运行中任务：{}，等待...",
                    execService.inFlightCount());
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        int remaining = execService.inFlightCount();
        if (remaining > 0) {
            log.warn("[Worker] drain 超时，{} 个任务未完成，将被 master 标记 FAILED（WORKER_RESTART）",
                    remaining);
        } else {
            log.info("[Worker] 所有运行中任务已完成，优雅退出");
        }

        execService.shutdown(5);
    }

    /** 是否仍在接收新任务（供 exec 端点检查）。 */
    public boolean isAccepting() {
        return accepting;
    }
}
