package com.dataweave.worker.localrun;

import com.dataweave.master.infrastructure.IsolatedDriverLoader;
import com.dataweave.worker.domain.ExecutionContext;
import com.dataweave.worker.domain.TaskExecutor.ExecutionResult;
import com.dataweave.worker.infrastructure.PythonTaskExecutor;
import com.dataweave.worker.infrastructure.ShellTaskExecutor;
import com.dataweave.worker.infrastructure.SqlTaskExecutor;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SC-002 黄金对照：同一 (type, content, ds) 经 {@code LocalRunMain}（本地 runner）与
 * 经服务器执行器（直接 new）执行，exitCode / stdout-stderr 分流 / 超时中止行为**逐项相等**。
 *
 * <p>这是子特性 D 的命门：证明本地 runtime 复用真实执行器，代码级一致（非口号）。
 * LocalRunMain 内部直接调用同一执行器实现 → 必然相等；本测试锁定该不变量，任何在 runner
 * 与执行器之间引入额外逻辑的回归都会被捕获。
 *
 * <p>SQL 的 {@code ExecutionResult.stdout} 字段为空（日志只经 onLine 回调），故 SQL 场景
 * 对照 onLine 序列（服务器 list vs runner 写出的 stdout）；SHELL/PYTHON 的 stdout 确定可精确对照。
 */
class LocalRunMainParityTest {

    private final LocalRunMain runner = new LocalRunMain();

    /** 可抛受检异常的 Runnable（runner.runResult 声明 throws Exception）。 */
    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** 捕获 runner 写到 System.out 的内容（验证管道直出 + 避免污染测试输出）。
     *  断言失败（AssertionError，属 Error）不被 catch(Exception) 捕获，正常传播让 JUnit 标记失败。 */
    private String captureStdout(ThrowingRunnable action) {
        PrintStream old = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            System.setOut(old);
        }
        return buf.toString(StandardCharsets.UTF_8);
    }

    private ExecutionContext ctx(String content, int timeout, String type) {
        return new ExecutionContext(content, null, 1, timeout, "TEST", type, null, null, null);
    }

    @Test
    void shellParityExitCodeStdoutSuccess() {
        String content = "echo parity-shell";
        ExecutionResult server = new ShellTaskExecutor().execute(ctx(content, 10, "SHELL"), l -> {});

        String captured = captureStdout(() -> {
            ExecutionResult local = runner.runResult(LocalRunArgs.of("SHELL", 10, null, content));
            assertThat(local.exitCode()).isEqualTo(server.exitCode());
            assertThat(local.success()).isEqualTo(server.success());
            assertThat(local.timedOut()).isEqualTo(server.timedOut());
            assertThat(local.truncated()).isEqualTo(server.truncated());
            // SHELL 的 stdout 确定性（echo 固定输出）→ 精确相等
            assertThat(local.stdout()).isEqualTo(server.stdout());
            assertThat(local.stderr()).isEqualTo(server.stderr());
        });
        assertThat(captured).contains("parity-shell");
    }

    @Test
    void pythonParityExitCodeStdout() {
        String content = "print('parity-py')";
        ExecutionResult server = new PythonTaskExecutor().execute(ctx(content, 10, "PYTHON"), l -> {});

        String captured = captureStdout(() -> {
            ExecutionResult local = runner.runResult(LocalRunArgs.of("PYTHON", 10, null, content));
            assertThat(local.exitCode()).isEqualTo(server.exitCode());
            assertThat(local.success()).isEqualTo(server.success());
            assertThat(local.timedOut()).isEqualTo(server.timedOut());
            assertThat(local.stdout()).isEqualTo(server.stdout());
            assertThat(local.exitCode()).isEqualTo(0);
        });
        assertThat(captured).contains("parity-py");
    }

    @Test
    void failureParityExitCodeMatches() {
        // PYTHON sys.exit(42) → exitCode 42（SC-004 失败任务非0）
        String content = "import sys; sys.exit(42)";
        ExecutionResult server = new PythonTaskExecutor().execute(ctx(content, 10, "PYTHON"), l -> {});

        captureStdout(() -> {
            ExecutionResult local = runner.runResult(LocalRunArgs.of("PYTHON", 10, null, content));
            assertThat(local.exitCode()).isEqualTo(server.exitCode());
            assertThat(local.exitCode()).isEqualTo(42);
            assertThat(local.success()).isEqualTo(server.success()).isFalse();
        });
    }

    @Test
    void timeoutParityBothTimedOutSameExitCode() {
        String content = "sleep 30";
        int timeout = 2;
        ExecutionResult server = new ShellTaskExecutor().execute(ctx(content, timeout, "SHELL"), l -> {});

        captureStdout(() -> {
            ExecutionResult local = runner.runResult(LocalRunArgs.of("SHELL", timeout, null, content));
            assertThat(local.timedOut()).isEqualTo(server.timedOut()).isTrue();
            assertThat(local.exitCode()).isEqualTo(server.exitCode()); // 两者均 -1（destroyForcibly）
            assertThat(local.success()).isEqualTo(server.success()).isFalse();
        });
    }

    @Test
    void sqlParityWithH2Datasource() throws Exception {
        // H2 内存库真实执行。SQL 日志含耗时（每次不同）→ stdout 不可逐字符对照，
        // 故断言 exitCode/success/message 逐项相等 + onLine 序列关键语义串一致。
        String jdbcUrl = "jdbc:h2:mem:parity_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        var dsRef = new ExecutionContext.DataSourceRef("h2mem", "H2", jdbcUrl, "sa", "");
        // drop if exists：server 先建表，local 复用同一 H2 库（DB_CLOSE_DELAY=-1 存活），
        // 第二次 create 会冲突；drop 先清，确保"同一 ds"两次执行各自成功。
        String sql = "drop table if exists t; create table t(id int); insert into t values (1); select * from t";
        ExecutionContext serverCtx = new ExecutionContext(sql, "2026-06-27", 1, 30, "TEST", "SQL", dsRef);

        List<String> serverLines = new ArrayList<>();
        ExecutionResult server = new SqlTaskExecutor(new IsolatedDriverLoader(new NoopDriverJarStorage()))
                .execute(serverCtx, serverLines::add);

        // 写 ds.json（Go CLI 生成格式：扁平 {name,typeCode,jdbcUrl,username,password}）
        Path dsJson = Files.createTempFile("dw-ds-", ".json");
        String json = "{\"name\":\"h2mem\",\"typeCode\":\"H2\",\"jdbcUrl\":\"" + jdbcUrl
                + "\",\"username\":\"sa\",\"password\":\"\"}";
        Files.writeString(dsJson, json, StandardCharsets.UTF_8);

        String captured = captureStdout(() -> {
            ExecutionResult local = runner.runResult(LocalRunArgs.of("SQL", 30, dsJson.toString(), sql));
            // 确定字段逐项相等
            assertThat(local.exitCode()).isEqualTo(server.exitCode());
            assertThat(local.success()).isEqualTo(server.success());
            assertThat(local.timedOut()).isEqualTo(server.timedOut());
            assertThat(local.message()).isEqualTo(server.message());
        });

        // onLine 序列语义一致：服务器 list 与 runner 写出的 stdout 都含连接成功 + 影响行数
        String serverLog = String.join("\n", serverLines);
        assertThat(serverLog).contains("连接成功", "影响 1 行");
        assertThat(captured).contains("连接成功", "影响 1 行");
    }

    @Test
    void unsupportedTypeRejected() {
        captureStdout(() -> {
            try {
                runner.runResult(LocalRunArgs.of("DATA_SYNC", 10, null, "x"));
                org.assertj.core.api.Assertions.fail("应拒绝不支持的任务类型");
            } catch (IllegalArgumentException e) {
                assertThat(e.getMessage()).contains("DATA_SYNC");
            }
        });
    }
}
