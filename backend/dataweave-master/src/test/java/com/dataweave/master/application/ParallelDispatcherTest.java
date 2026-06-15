package com.dataweave.master.application;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ParallelDispatcher} 并行下发器：把调度内核「串行阻塞下发」的瓶颈改成有界线程池并行 fan-out，
 * 并以屏障语义保持轮次串行。下发阶段不持 DB 锁，并行不触碰死锁防御不变量。
 */
class ParallelDispatcherTest {

    private ParallelDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new ParallelDispatcher(4);
    }

    @AfterEach
    void tearDown() {
        dispatcher.shutdown();
    }

    @Test
    void dispatchesEveryItemExactlyOnce() {
        Set<String> seen = ConcurrentHashMap.newKeySet();
        dispatcher.dispatchAll(List.of("a", "b", "c"), seen::add, (item, err) -> {});
        assertThat(seen).containsExactlyInAnyOrder("a", "b", "c");
    }

    @Test
    void failedItemRoutedToOnFailureWithCause_othersStillDispatched() {
        Set<String> ok = ConcurrentHashMap.newKeySet();
        List<String> failed = new CopyOnWriteArrayList<>();
        List<String> causes = new CopyOnWriteArrayList<>();
        dispatcher.dispatchAll(List.of("a", "b", "c"),
                item -> {
                    if (item.equals("b")) {
                        throw new RuntimeException("boom");
                    }
                    ok.add(item);
                },
                (item, err) -> {
                    failed.add(item);
                    causes.add(err.getMessage());
                });
        assertThat(ok).containsExactlyInAnyOrder("a", "c");
        assertThat(failed).containsExactly("b");
        assertThat(causes).containsExactly("boom");
    }

    @Test
    void itemsDispatchedConcurrently_notSerially() {
        // 两个 item 必须都抵达同一屏障(parties=2)才能放行；若串行执行，第一个会一直等不到第二个
        // → 超时抛错落 onFailure。failed 为空即证明二者真并行。
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<String> failed = new CopyOnWriteArrayList<>();
        dispatcher.dispatchAll(List.of("x", "y"),
                item -> {
                    try {
                        barrier.await(2, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                (item, err) -> failed.add(item));
        assertThat(failed).isEmpty();
    }

    @Test
    void awaitsAllBeforeReturning() {
        // 屏障语义：dispatchAll 返回时所有 item 必须已完成，以保持调度轮次串行。
        AtomicInteger done = new AtomicInteger();
        dispatcher.dispatchAll(List.of("a", "b", "c", "d"),
                item -> {
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    done.incrementAndGet();
                },
                (item, err) -> {});
        assertThat(done.get()).isEqualTo(4);
    }

    @Test
    void emptyList_isNoop() {
        dispatcher.dispatchAll(List.of(),
                item -> {
                    throw new AssertionError("空列表不该调用 action");
                },
                (item, err) -> {
                    throw new AssertionError("空列表不该调用 onFailure");
                });
        // 无异常即通过
    }
}
