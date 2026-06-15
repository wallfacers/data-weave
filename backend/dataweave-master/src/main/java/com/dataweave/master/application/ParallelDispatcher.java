package com.dataweave.master.application;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 有界线程池并行下发器：把 {@code SchedulerKernel.runRound} 原本「单调度线程串行阻塞下发」的瓶颈
 * 改成有界并行 fan-out —— 一批已认领（已 CAS 置 DISPATCHED）的实例同时下发，各自的 HTTP 往返互相重叠。
 *
 * <p><b>死锁安全</b>：下发发生在认领事务<em>提交之后</em>（死锁防御不变量④：事务内只落状态，HTTP 下发在事务外），
 * 因此并行段<b>不持有任何数据库锁</b>，无从构成 task→workflow 锁序环；每项至多做一次独立的单行 CAS
 * （{@code casRequeue}）失败回退。并行只是重叠彼此独立的 I/O，触碰不到不变量①②③。
 *
 * <p><b>屏障语义</b>：{@link #dispatchAll} 用 {@code invokeAll} 等全部完成才返回，调度轮次仍由内核的
 * {@code running} 标志串行——并行只在「一轮之内」，轮与轮之间语义不变。
 *
 * <p>并行度 {@code scheduler.dispatch-parallelism}（默认 16）。线程为 daemon，随 JVM 退出，
 * {@link #shutdown()} 在容器销毁时回收。注意并行度宜与数据源连接池容量相称：每次下发会借一次连接做
 * 节点寻址/失败回退（瞬时持有），并行度远超连接池时会有连接排队（非死锁，仅短暂等待）。
 */
@Component
public class ParallelDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ParallelDispatcher.class);

    private final ExecutorService pool;
    private final int parallelism;

    public ParallelDispatcher(@Value("${scheduler.dispatch-parallelism:16}") int parallelism) {
        this.parallelism = Math.max(1, parallelism);
        AtomicInteger seq = new AtomicInteger();
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "dw-dispatch-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        this.pool = Executors.newFixedThreadPool(this.parallelism, tf);
        log.info("[ParallelDispatcher] 并行下发就绪，parallelism={}", this.parallelism);
    }

    /**
     * 并行下发一批项：每项在池内执行 {@code action}；抛异常则路由到 {@code onFailure(item, cause)}
     * （由调用方做单行 CAS 回退）。全部完成才返回（屏障，保持轮次串行）。
     *
     * @param items     待下发项（空/null 直接返回）
     * @param action    单项下发动作（阻塞 I/O）
     * @param onFailure 单项失败回调，收到该项与失败原因
     */
    public <T> void dispatchAll(List<T> items, Consumer<T> action, BiConsumer<T, Throwable> onFailure) {
        if (items == null || items.isEmpty()) {
            return;
        }
        List<Callable<Void>> tasks = new ArrayList<>(items.size());
        for (T item : items) {
            tasks.add(() -> {
                try {
                    action.accept(item);
                } catch (Exception e) {
                    onFailure.accept(item, e);
                }
                return null;
            });
        }
        try {
            pool.invokeAll(tasks);  // 屏障：全部完成才返回
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[ParallelDispatcher] 下发被中断，未竟项交由下一轮/租约恢复兜底重派");
        }
    }

    @PreDestroy
    public void shutdown() {
        pool.shutdownNow();
        try {
            pool.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
