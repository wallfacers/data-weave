package com.dataweave.worker.application;

import com.dataweave.master.i18n.Messages;
import com.dataweave.master.infrastructure.IsolatedDriverLoader;
import com.dataweave.worker.domain.ExecutionContext;
import com.dataweave.worker.domain.TaskExecutor.ExecutionResult;
import com.dataweave.worker.infrastructure.EchoTaskExecutor;
import com.dataweave.worker.infrastructure.PythonTaskExecutor;
import com.dataweave.worker.infrastructure.ShellTaskExecutor;
import com.dataweave.worker.infrastructure.SparkTaskExecutor;
import com.dataweave.worker.infrastructure.SqlTaskExecutor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * distributed 执行器分发测试（SC-003 / contracts C4.1，FR-016）：WorkerExecService 按任务类型选执行器，
 * 镜像 all-in-one 的 byType 分发——SQL 选 SQL 执行器（不再被当 SHELL 跑）、ECHO 选 ECHO、未知类型可辨识失败。
 *
 * <p>注入的 Map 以 bean 名为键（大小写不一），验证构造时正确改建按 type 的映射。
 */
class WorkerExecServiceDispatchTest {

    private WorkerExecService service;
    private static Messages messages;

    @BeforeAll
    static void setUpMessages() {
        ReloadableResourceBundleMessageSource ms = new ReloadableResourceBundleMessageSource();
        ms.setBasename("classpath:messages");
        ms.setDefaultEncoding("UTF-8");
        ms.setFallbackToSystemLocale(false);
        messages = new Messages(ms);
    }

    @BeforeEach
    void setup() {
        // 注入键刻意用小写 bean 名（模拟 Spring 注入），验证 byType 构造按 type().toUpperCase() 重建映射
        Map<String, com.dataweave.worker.domain.TaskExecutor> executors = new HashMap<>();
        executors.put("shell", new ShellTaskExecutor());
        executors.put("sql", new SqlTaskExecutor(mock(IsolatedDriverLoader.class)));
        executors.put("python", new PythonTaskExecutor());
        executors.put("echo", new EchoTaskExecutor());
        executors.put("spark", new SparkTaskExecutor());
        service = new WorkerExecService(executors, messages);
    }

    /** SC-003：SQL 任务在 distributed 路径选 SQL 执行器（非 SHELL）。
     *  SQL 无数据源 → SqlTaskExecutor 返回 SKIPPED；若被 SHELL 跑 "select 1" 会 bash 报错 exit≠0 skipped=false。 */
    @Test
    void sqlTask_selectsSqlExecutor_notShell_returnsSkipped() {
        ExecutionContext ctx = new ExecutionContext("select 1", null, 1, 10, null, "SQL", null);
        ExecutionResult r = service.executeSync(UUID.randomUUID(), 1, ctx, null);

        assertThat(r).isNotNull();
        assertThat(r.skipped()).isTrue();          // SQL 无数据源 → SKIPPED（证明走 SQL 执行器）
        assertThat(r.success()).isFalse();
    }

    /** ECHO 任务选 EchoTaskExecutor：回显 content 成功（SHELL 跑 "dw-echo-marker" 会 command not found exit 127）。 */
    @Test
    void echoTask_selectsEchoExecutor_success() {
        ExecutionContext ctx = new ExecutionContext("dw-echo-marker", null, 1, 10, null, "ECHO", null);
        ExecutionResult r = service.executeSync(UUID.randomUUID(), 1, ctx, l -> {});

        assertThat(r.success()).isTrue();
        assertThat(r.exitCode()).isEqualTo(0);
        assertThat(r.stdout()).contains("dw-echo-marker");
    }

    /** PYTHON 任务选 PythonTaskExecutor：print 成功（SHELL 跑 Python 语法会语法错）。 */
    @Test
    void pythonTask_selectsPythonExecutor_success() {
        ExecutionContext ctx = new ExecutionContext("print('dw-py-dispatch')", null, 1, 10, null, "PYTHON", null);
        ExecutionResult r = service.executeSync(UUID.randomUUID(), 1, ctx, l -> {});

        assertThat(r.success()).isTrue();
        assertThat(r.exitCode()).isEqualTo(0);
    }

    /** contracts C4.1：未知类型不静默当 SHELL、不伪装成功 → NO_EXECUTOR 可辨识失败。 */
    @Test
    void unknownType_reportsNoExecutor_notSilentlyShell() {
        ExecutionContext ctx = new ExecutionContext("x", null, 1, 10, null, "DATA_SYNC", null);
        ExecutionResult r = service.executeSync(UUID.randomUUID(), 1, ctx, null);

        assertThat(r).isNotNull();
        assertThat(r.success()).isFalse();
        assertThat(r.skipped()).isFalse();
        assertThat(r.message()).contains("DATA_SYNC");  // 可定位：哪个类型无执行器
    }
}
