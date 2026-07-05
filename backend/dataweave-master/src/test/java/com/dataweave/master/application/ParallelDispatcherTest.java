package com.dataweave.master.application;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * 046 {@link ParallelDispatcher} 异步并行下发器:dispatchAllAsync fire-and-forget
 * (去 invokeAll 屏障,claim 不等 dispatch)。下发在认领事务外,不持 DB 锁,触不到死锁不变量①②③。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ParallelDispatcherTest {

    @Mock SchedulerMetrics metrics;

    private ParallelDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new ParallelDispatcher(4, 100, metrics);
    }

    @AfterEach
    void tearDown() {
        dispatcher.shutdown();
    }

    @Test
    void dispatchAllAsync_dispatchesEveryItemExactlyOnce() throws Exception {
        Set<String> seen = ConcurrentHashMap.newKeySet();
        CountDownLatch latch = new CountDownLatch(3);
        dispatcher.dispatchAllAsync(List.of("a", "b", "c"),
                item -> { seen.add(item); latch.countDown(); },
                (item, err) -> latch.countDown());
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(seen).containsExactlyInAnyOrder("a", "b", "c");
    }

    @Test
    void failedItemRoutedToOnFailureWithCause_othersStillDispatched() throws Exception {
        Set<String> ok = ConcurrentHashMap.newKeySet();
        List<String> failed = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(3);
        dispatcher.dispatchAllAsync(List.of("a", "b", "c"),
                item -> {
                    try {
                        if (item.equals("b")) {
                            throw new RuntimeException("boom");
                        }
                        ok.add(item);
                    } finally {
                        latch.countDown();
                    }
                },
                (item, err) -> { failed.add(item); latch.countDown(); });
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        // onFailure 在 dispatchExecutor 线程异步触发,稍等确保可见
        Thread.sleep(150);
        assertThat(ok).containsExactlyInAnyOrder("a", "c");
        assertThat(failed).containsExactly("b");
    }

    @Test
    void emptyList_isNoop() {
        dispatcher.dispatchAllAsync(List.of(),
                item -> { throw new AssertionError("空列表不该调用 action"); },
                (item, err) -> { throw new AssertionError("空列表不该调用 onFailure"); });
        // 无异常即通过(fire-and-forget 立即返回)
    }

    @Test
    void queueFull_fallsBackToSync_andMarksQueueFull_noLoss() throws Exception {
        // executor=1 + queue=1:并发提交多个慢任务必然触发拒绝 → RejectedExecutionHandler 降级同步 + markQueueFull
        ParallelDispatcher small = new ParallelDispatcher(1, 1, metrics);
        AtomicInteger count = new AtomicInteger();
        // 用 CountDownLatch 起跑,让 4 个提交尽量抢同一时刻(提高撞满概率)
        CountDownLatch start = new CountDownLatch(1);
        Thread[] callers = new Thread[4];
        for (int i = 0; i < 4; i++) {
            final String item = "x" + i;
            callers[i] = new Thread(() -> {
                try { start.await(); } catch (InterruptedException e) { return; }
                small.dispatchAllAsync(List.of(item),
                        it -> {
                            try { Thread.sleep(30); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                            count.incrementAndGet();
                        },
                        (it, err) -> {});
            });
            callers[i].start();
        }
        start.countDown();
        for (Thread t : callers) t.join(3000);
        small.shutdown();
        // 降级同步不丢:4 个 item 都执行完
        assertThat(count.get()).isEqualTo(4);
        // 队列满至少发生过一次(markDispatchQueueFull 被调)
        verify(metrics, atLeastOnce()).markDispatchQueueFull();
    }
}
