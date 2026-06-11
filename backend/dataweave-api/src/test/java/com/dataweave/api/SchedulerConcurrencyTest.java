package com.dataweave.api;

import com.dataweave.master.application.InstanceStateMachine;
import com.dataweave.master.domain.CronFire;
import com.dataweave.master.domain.CronFireRepository;
import com.dataweave.master.domain.InstanceStates;
import com.dataweave.master.domain.TaskInstance;
import com.dataweave.master.domain.TaskInstanceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 调度内核死锁防御不变量的并发回归（task 2.1/2.7/2.12，H2 真库）：
 * 乐观 CAS 竞态唯一、cron_fire 护栏表多 master 防重、多线程竞争认领每实例恰一次。
 */
@SpringBootTest
@ActiveProfiles("h2")
class SchedulerConcurrencyTest {

    @Autowired
    InstanceStateMachine stateMachine;
    @Autowired
    TaskInstanceRepository taskInstanceRepository;
    @Autowired
    CronFireRepository cronFireRepository;

    // 挂一个不存在的 workflow_instance_id：内核可运行查询的 wi.state 子查询返回 NULL → 行被排除，
    // 后台 poll/wake 不会抢走这些测试实例，保证并发断言隔离（CAS 本身不依赖该字段）。
    private static final UUID DETACHED_WF = UUID.fromString("00000000-0000-7000-8000-0000000000ff");

    private UUID createWaiting() {
        TaskInstance ti = new TaskInstance();
        ti.setTenantId(1L);
        ti.setProjectId(1L);
        ti.setTaskId(1L);
        ti.setWorkflowInstanceId(DETACHED_WF);
        ti.setRunMode("NORMAL");
        ti.setState(InstanceStates.WAITING);
        ti.setAttempt(0);
        ti.setCreatedAt(LocalDateTime.now());
        ti.setUpdatedAt(LocalDateTime.now());
        ti.setDeleted(0);
        ti.setVersion(0L);
        return taskInstanceRepository.save(ti).getId();
    }

    @Test
    void casRace_onlyOneThreadWins() throws Exception {
        UUID id = createWaiting();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger wins = new AtomicInteger();
        List<java.util.concurrent.Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            fs.add(pool.submit(() -> {
                await(start);
                if (stateMachine.casTaskState(id, InstanceStates.WAITING, InstanceStates.RUNNING)) {
                    wins.incrementAndGet();
                }
            }));
        }
        start.countDown();
        for (var f : fs) {
            f.get();
        }
        pool.shutdown();
        assertThat(wins.get()).as("CAS 竞态恰一个胜出").isEqualTo(1);
        assertThat(taskInstanceRepository.findById(id).orElseThrow().getState()).isEqualTo(InstanceStates.RUNNING);
    }

    @Test
    void competingClaims_eachInstanceDispatchedExactlyOnce() throws Exception {
        int instances = 20;
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < instances; i++) {
            ids.add(createWaiting());
        }
        int masters = 6;
        ExecutorService pool = Executors.newFixedThreadPool(masters);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger totalDispatched = new AtomicInteger();
        List<java.util.concurrent.Future<?>> fs = new ArrayList<>();
        for (int m = 0; m < masters; m++) {
            final String node = "node-" + m;
            fs.add(pool.submit(() -> {
                await(start);
                for (UUID id : ids) {
                    if (stateMachine.casDispatch(id, InstanceStates.WAITING, node,
                            LocalDateTime.now().plusSeconds(60), 1)) {
                        totalDispatched.incrementAndGet();
                    }
                }
            }));
        }
        start.countDown();
        for (var f : fs) {
            f.get();
        }
        pool.shutdown();
        // 每个实例恰被一个 master 认领，无重复下发
        assertThat(totalDispatched.get()).isEqualTo(instances);
        for (UUID id : ids) {
            assertThat(taskInstanceRepository.findById(id).orElseThrow().getState())
                    .isEqualTo(InstanceStates.DISPATCHED);
        }
    }

    @Test
    void cronFireGuardrail_dedupsConcurrentInserts() throws Exception {
        long workflowId = 987654L;
        LocalDateTime fireTime = LocalDateTime.of(2026, 6, 11, 8, 0, 0);
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        List<java.util.concurrent.Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            fs.add(pool.submit(() -> {
                await(start);
                try {
                    CronFire f = new CronFire(workflowId, fireTime);
                    f.setCreatedAt(LocalDateTime.now());
                    cronFireRepository.save(f);
                    ok.incrementAndGet();
                } catch (Exception e) {
                    conflict.incrementAndGet();
                }
            }));
        }
        start.countDown();
        for (var f : fs) {
            f.get();
        }
        pool.shutdown();
        assertThat(ok.get()).as("护栏表唯一键：恰一次插入成功").isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(threads - 1);
        assertThat(cronFireRepository.findByWorkflowIdAndScheduledFireTime(workflowId, fireTime)).isPresent();
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
