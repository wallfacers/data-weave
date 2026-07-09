package com.dataweave.master.application;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 异步并行下发器(046 dispatch 并行化)。
 *
 * <p>把 {@code SchedulerKernel.runRound} 原本「claim 事务 + {@code invokeAll} 屏障 dispatch」捆成单线程的瓶颈,
 * 改成 <b>fire-and-forget</b> —— claim 事务提交后,逐个 submit 到 {@code dispatchExecutor} 立即返回,
 * 下一轮 claim 可马上开始(解除 claim↔dispatch 串行)。下发在独立线程池异步并行 fan-out。
 *
 * <p><b>死锁安全</b>(不变量④):下发发生在认领事务<em>提交之后</em>,并行段不持任何 DB 锁,
 * 无从构成 task→workflow 锁序环;每项至多做一次独立单行 CAS({@code casRequeue})失败回退。
 * 触不到不变量①②③。
 *
 * <p><b>背压</b>:{@code dispatchExecutor} 用有界队列({@code dispatch-queue-capacity});队列 + 线程都满时
 * {@link RejectedExecutionHandler} 降级为<b>同步下发</b>(当前 claim 线程跑)+ {@code markDispatchQueueFull},
 * 不丢 DispatchCommand(同 045 fireQueue 满)。慢 worker 由 WebClient 3s 超时兜底(T004),不挂线程。
 *
 * <p>配置:{@code scheduler.dispatch-executor-threads}(默认 64)/ {@code dispatch-queue-capacity}(默认 2000)。
 */
@Component
public class ParallelDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ParallelDispatcher.class);

    private final ThreadPoolExecutor dispatchExecutor;
    private final SchedulerMetrics metrics;

    public ParallelDispatcher(@Value("${scheduler.dispatch-executor-threads:64}") int executorThreads,
                              @Value("${scheduler.dispatch-queue-capacity:2000}") int queueCapacity,
                              SchedulerMetrics metrics) {
        this.metrics = metrics;
        AtomicInteger seq = new AtomicInteger();
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "dw-dispatch-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        // 队列 + 线程都满 → 当前线程同步跑(降级,不丢)+ 标背压
        RejectedExecutionHandler reject = (r, exec) -> {
            metrics.markDispatchQueueFull();
            r.run();
        };
        this.dispatchExecutor = new ThreadPoolExecutor(
                Math.max(1, executorThreads), Math.max(1, executorThreads),
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(Math.max(1, queueCapacity)),
                tf, reject);
        log.info("[ParallelDispatcher] 异步下发就绪:executorThreads={} queueCapacity={}", executorThreads, queueCapacity);
    }

    /**
     * 046 异步并行下发:逐个 submit 到 {@code dispatchExecutor},<b>立即返回</b>(去 {@code invokeAll} 屏障)。
     * claim 线程不等 dispatch。每项在池内执行 {@code action};抛异常路由到 {@code onFailure}(调用方 casRequeue)。
     *
     * @param items     待下发项(空/null 直接返回)
     * @param action    单项下发动作(阻塞 I/O,gateway::dispatch)
     * @param onFailure 单项失败回调(收到该项与失败原因,做单行 CAS 回退)
     */
    public <T> void dispatchAllAsync(List<T> items, Consumer<T> action, BiConsumer<T, Throwable> onFailure) {
        if (items == null || items.isEmpty()) {
            return;
        }
        for (T item : items) {
            dispatchExecutor.execute(() -> {
                try {
                    action.accept(item);
                } catch (Throwable e) {
                    // catch Throwable 而非仅 Exception：Error（OOM/NoClassDefFound 等）同样需要
                    // 触发 onFailure 回退（casRequeue），避免线程静默死亡后实例永久滞留 DISPATCHED。
                    onFailure.accept(item, e);
                }
            });
        }
    }

    /** 当前 dispatch 队列深度(背压观测,供 SchedulerMetrics gauge)。 */
    public int queueSize() {
        return dispatchExecutor.getQueue().size();
    }

    @PreDestroy
    public void shutdown() {
        dispatchExecutor.shutdown();
        try {
            if (!dispatchExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("[ParallelDispatcher] 5s 内仍有未完下发,residual 交由 lease/下一轮兜底;shutdownNow");
                dispatchExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            dispatchExecutor.shutdownNow();
        }
    }
}
