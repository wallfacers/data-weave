package com.dataweave.worker.localrun;

import com.dataweave.master.infrastructure.IsolatedDriverLoader;
import com.dataweave.worker.domain.ExecutionContext;
import com.dataweave.worker.domain.TaskExecutor;
import com.dataweave.worker.infrastructure.PythonTaskExecutor;
import com.dataweave.worker.infrastructure.ShellTaskExecutor;
import com.dataweave.worker.infrastructure.SqlTaskExecutor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本地轻量 runtime 入口（子特性 D / FR-005/006，US2 核心）。
 *
 * <p>脱离 master/scheduler 独立运行（**不启 Spring 上下文**），复用 {@code dataweave-worker}
 * 的真实执行器（Shell/Sql/Python）执行 stdin 传入的脚本体，逐行 stdout 直出，
 * {@code System.exit(exitCode)} 忠实透传执行结果。被 Go CLI 以子进程调起。
 *
 * <p><b>SC-002 不变量</b>：同一 (type, content, ds) 经本 runner 与经服务器执行器执行，
 * exitCode / stdout-stderr 分流 / 超时中止行为**逐项相等**——因为 runner 内部直接调用同一执行器
 * 实现（代码级一致，非口号）。{@code LocalRunMainParityTest} 断言之。
 *
 * <p>边界：本类只依赖 {@code dataweave-worker} 执行器 + 极简 ds-json 解析，不依赖
 * {@code dataweave-master.filecontract}（B 的解析在服务器 C 侧，CLI 只搬运字节）。
 */
public class LocalRunMain {

    /** 扁平 JSON "key":"value" 提取（ds-json 由 Go CLI 生成，格式可控、扁平、无嵌套）。 */
    private static final Pattern JSON_KV =
            Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    public static void main(String[] args) {
        try {
            LocalRunArgs parsed = LocalRunArgs.parse(args);
            LocalRunMain m = new LocalRunMain();
            TaskExecutor.ExecutionResult r = m.runResult(parsed);
            // 失败时把执行器摘要写到 stderr（可定位，如缺 python3 / 超时），stdout 仍为执行输出。
            if (!r.success() && r.message() != null && !r.message().isBlank()) {
                System.err.println(r.message());
            }
            System.out.flush();
            System.exit(r.exitCode());
        } catch (IllegalArgumentException e) {
            System.err.println("[LocalRunMain] 参数错误（exit 2）: " + e.getMessage());
            System.exit(2);
        } catch (Exception e) {
            System.err.println("[LocalRunMain] 执行失败（exit 4）: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(4);
        }
    }

    /** 执行并返回退出码（main 用，SC-004 失败任务返回非0）。 */
    public int run(LocalRunArgs args) throws Exception {
        return runResult(args).exitCode();
    }

    /**
     * 执行并返回完整 {@link TaskExecutor.ExecutionResult}（SC-002 黄金对照用）；
     * 同时把每行输出实时 {@code println} 到 stdout（生产管道直出）。
     * 返回的 result 来自执行器自身采集（与 onLine 回调独立，确保对照权威）。
     */
    public TaskExecutor.ExecutionResult runResult(LocalRunArgs args) throws Exception {
        TaskExecutor executor = selectExecutor(args.type());
        ExecutionContext ctx = buildContext(args);
        TaskExecutor.ExecutionResult r = executor.execute(ctx, this::printLine);
        System.out.flush();
        return r;
    }

    private void printLine(String line) {
        System.out.println(line);
    }

    /** 按 type 选真实执行器（与服务器同构：直接 new，不经 Spring）。 */
    private TaskExecutor selectExecutor(String type) {
        return switch (type) {
            case "SHELL" -> new ShellTaskExecutor();
            // SqlTaskExecutor 构造需 IsolatedDriverLoader；本地走 DriverManager 分支，loader 不会被真正调用。
            case "SQL" -> new SqlTaskExecutor(new IsolatedDriverLoader(new NoopDriverJarStorage()));
            case "PYTHON" -> new PythonTaskExecutor();
            default -> throw new IllegalArgumentException(
                    "不支持的任务类型: " + type + "（本地 MVP 仅 SHELL/SQL/PYTHON，FR-007）");
        };
    }

    private ExecutionContext buildContext(LocalRunArgs args) throws Exception {
        ExecutionContext.DataSourceRef dsRef = null;
        String pythonConfigPath = null;
        if (args.dsJsonPath() != null && !args.dsJsonPath().isBlank()) {
            if ("SQL".equals(args.type())) {
                dsRef = readDataSourceRef(args.dsJsonPath());
            } else if ("PYTHON".equals(args.type())) {
                // PYTHON 经环境变量 DW_DATASOURCE_CONFIG 读配置文件路径（PythonTaskExecutor 注入）
                pythonConfigPath = args.dsJsonPath();
            }
            // SHELL 不消费 ds-json（Shell 数据源走 shellEnvVars，本期本地不注入）
        }
        return new ExecutionContext(args.content(), null, 1, args.timeoutSeconds(),
                "TEST", args.type(), dsRef, null, pythonConfigPath);
    }

    /** 读 --ds-json 文件 → DataSourceRef（{name,typeCode,jdbcUrl,username,password}）。 */
    private ExecutionContext.DataSourceRef readDataSourceRef(String path) throws Exception {
        String json = Files.readString(Path.of(path), StandardCharsets.UTF_8);
        Map<String, String> m = new LinkedHashMap<>();
        Matcher matcher = JSON_KV.matcher(json);
        while (matcher.find()) {
            m.put(matcher.group(1), unescape(matcher.group(2)));
        }
        if (!m.containsKey("jdbcUrl")) {
            throw new IllegalArgumentException("ds-json 缺少 jdbcUrl: " + path);
        }
        return new ExecutionContext.DataSourceRef(
                m.getOrDefault("name", ""), m.getOrDefault("typeCode", ""),
                m.get("jdbcUrl"), m.get("username"), m.get("password"));
    }

    private static String unescape(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\")
                .replace("\\n", "\n").replace("\\t", "\t").replace("\\r", "\r");
    }
}
