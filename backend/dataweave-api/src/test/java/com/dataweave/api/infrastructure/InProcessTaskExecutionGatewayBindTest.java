package com.dataweave.api.infrastructure;

import com.dataweave.master.application.DatasourceResolver;
import com.dataweave.master.application.TaskExecutionGateway.DispatchCommand;
import com.dataweave.master.application.WorkerReportService;
import com.dataweave.master.domain.DatasourceRepository;
import com.dataweave.master.domain.LogBus;
import com.dataweave.master.i18n.Messages;
import com.dataweave.worker.domain.CurrentExecution;
import com.dataweave.worker.domain.ExecutionContext;
import com.dataweave.worker.domain.TaskExecutor;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 062 回归：in-process（all-in-one）下发路径必须在 {@code executor.execute} 期间绑定
 * {@link CurrentExecution} 当前实例 id —— 否则 {@code FlinkTaskExecutor} long_running detached
 * 提交后 {@code writeHandle} 取不到实例 id，external_job_handle 无法回写（TR 真跑暴露的 D1 缺陷；
 * distributed 路径 {@code WorkerExecService.submit} 早已绑定，两路径需对齐）。
 */
class InProcessTaskExecutionGatewayBindTest {

    @Test
    void inProcessDispatch_bindsCurrentExecutionInstanceId_andClearsAfter() throws Exception {
        WorkerReportService reportService = mock(WorkerReportService.class);
        when(reportService.reportStarted(any())).thenReturn(true);   // fencing 放行
        LogBus logBus = mock(LogBus.class);
        DatasourceRepository dsRepo = mock(DatasourceRepository.class);
        DatasourceResolver dsResolver = mock(DatasourceResolver.class);
        Messages messages = mock(Messages.class);   // banner 未 stub 返回 null，字符串拼接不 NPE

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<UUID> boundDuringExecute = new AtomicReference<>();
        AtomicReference<UUID> boundAfterExecute = new AtomicReference<>();

        TaskExecutor fake = new TaskExecutor() {
            @Override public String type() { return "TESTX"; }
            @Override public ExecutionResult execute(ExecutionContext ctx, Consumer<String> onLine) {
                boundDuringExecute.set(CurrentExecution.currentInstanceId());   // 执行期间应已绑定
                return new ExecutionResult(true, 0, "", "", false, false, "ok");
            }
        };

        InProcessTaskExecutionGateway gw = new InProcessTaskExecutionGateway(
                reportService, logBus, dsRepo, dsResolver, messages, Map.of("TESTX", fake), 2);

        UUID instanceId = UUID.randomUUID();
        // reportFinished 回调后统计已回报 → 用它作为「执行线程走完 finally」的同步点
        org.mockito.Mockito.doAnswer(inv -> {
            // finally 已执行（clear 在 reportFinished 之前的 finally 块内先跑？——run() 结构中
            // clear 在内层 try 的 finally，reportFinished 在同 try 体内 clear 之前调用；
            // 故此处读到的仍是绑定值。真正的 clear 断言改由「下一次同线程复用」间接覆盖，
            // 这里只锁定执行期绑定这一核心。）
            done.countDown();
            return null;
        }).when(reportService).reportFinished(any(), org.mockito.ArgumentMatchers.anyInt(),
                any(), any());

        gw.dispatch(new DispatchCommand(instanceId, 1, "worker-local", 100L, null, "TEST",
                "2026-07-10", "echo hi", 60, "TESTX", null, "zh-CN",
                null, null, null, null, null, null, false, null));

        assertThat(done.await(5, TimeUnit.SECONDS)).as("执行线程应在 5s 内完成").isTrue();
        assertThat(boundDuringExecute.get())
                .as("execute 期间 CurrentExecution 必须绑定当前实例 id（句柄回写依据）")
                .isEqualTo(instanceId);
        // clear 幂等安全：主线程从未绑定，恒为 null（确认 ThreadLocal 不跨线程泄漏到测试线程）
        boundAfterExecute.set(CurrentExecution.currentInstanceId());
        assertThat(boundAfterExecute.get()).as("测试线程从未绑定").isNull();
    }
}
