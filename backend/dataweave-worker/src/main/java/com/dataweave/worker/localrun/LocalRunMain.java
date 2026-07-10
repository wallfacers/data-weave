package com.dataweave.worker.localrun;

import com.dataweave.master.infrastructure.IsolatedDriverLoader;
import com.dataweave.worker.domain.ExecutionContext;
import com.dataweave.worker.domain.TaskExecutor;
import com.dataweave.worker.infrastructure.EchoTaskExecutor;
import com.dataweave.worker.infrastructure.HiveTaskExecutor;
import com.dataweave.worker.infrastructure.PythonTaskExecutor;
import com.dataweave.worker.infrastructure.ShellTaskExecutor;
import com.dataweave.worker.infrastructure.SparkTaskExecutor;
import com.dataweave.worker.infrastructure.SqlTaskExecutor;
import com.dataweave.worker.infrastructure.DataXTaskExecutor;
import com.dataweave.worker.infrastructure.SeaTunnelTaskExecutor;
import com.dataweave.worker.infrastructure.FlinkTaskExecutor;

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
 * 的真实执行器（Shell/Sql/Python/Spark）执行 stdin 传入的脚本体，逐行 stdout 直出，
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
            // 失败或跳过时把执行器摘要写到 stderr（可定位，如缺 Spark 环境 / 超时），stdout 仍为执行输出。
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
            case "SPARK" -> new SparkTaskExecutor();
            case "ECHO" -> new EchoTaskExecutor();
            case "DATAX" -> new DataXTaskExecutor();
            case "SEATUNNEL" -> new SeaTunnelTaskExecutor();
            case "FLINK" -> new FlinkTaskExecutor();
            // HiveTaskExecutor extends SqlTaskExecutor，构造需 IsolatedDriverLoader；本地走 DriverManager 分支
            case "HIVE" -> new HiveTaskExecutor(new IsolatedDriverLoader(new NoopDriverJarStorage()));
            default -> throw new IllegalArgumentException(
                    "不支持的任务类型: " + type + "（本地支持 SHELL/SQL/PYTHON/ECHO/SPARK/DATAX/SEATUNNEL/HIVE/FLINK，FR-010）");
        };
    }

    private ExecutionContext buildContext(LocalRunArgs args) throws Exception {
        ExecutionContext.DataSourceRef dsRef = null;
        String pythonConfigPath = null;
        ExecutionContext.SparkSubmitRef sparkRef = null;
        ExecutionContext.EngineSubmitRef engineRef = null;
        if (args.dsJsonPath() != null && !args.dsJsonPath().isBlank()) {
            if ("SQL".equals(args.type()) || "HIVE".equals(args.type())) {
                dsRef = readDataSourceRef(args.dsJsonPath());
            } else if ("PYTHON".equals(args.type())) {
                // PYTHON 经环境变量 DW_DATASOURCE_CONFIG 读配置文件路径（PythonTaskExecutor 注入）
                pythonConfigPath = args.dsJsonPath();
            }
            // SHELL 不消费 ds-json（Shell 数据源走 shellEnvVars，本期本地不注入）
        }
        // SPARK：集群配置来自 ds-json（若绑数据源），内容形态来自 CLI flag；
        // 即使未绑数据源也建 ref（sparkHome/master 缺 → 执行器判 SKIPPED，不丢 sparkMode）。
        if ("SPARK".equals(args.type())) {
            sparkRef = readSparkRef(args);
        }
        // 通用引擎（FLINK/DATAX/SEATUNNEL）：engineHome 优先从 ds-json，否则从环境变量 *_HOME
        if ("FLINK".equals(args.type()) || "DATAX".equals(args.type()) || "SEATUNNEL".equals(args.type())) {
            engineRef = buildEngineRef(args);
        }
        return new ExecutionContext(args.content(), null, 1, args.timeoutSeconds(),
                "TEST", args.type(), dsRef, null, pythonConfigPath, sparkRef, engineRef);
    }

    /** 读 --ds-json 文件 → DataSourceRef（{name,typeCode,jdbcUrl,username,password}）。 */
    private ExecutionContext.DataSourceRef readDataSourceRef(String path) throws Exception {
        Map<String, String> m = parseFlatKv(Files.readString(Path.of(path), StandardCharsets.UTF_8));
        if (!m.containsKey("jdbcUrl")) {
            throw new IllegalArgumentException("ds-json 缺少 jdbcUrl: " + path);
        }
        return new ExecutionContext.DataSourceRef(
                m.getOrDefault("name", ""), m.getOrDefault("typeCode", ""),
                m.get("jdbcUrl"), m.get("username"), m.get("password"));
    }

    /**
     * 合成本地 SparkSubmitRef：集群配置（sparkHome/master/deployMode/queue）从扁平 ds-json 读（绑数据源时），
     * 内容形态（sparkMode/jarPath/mainClass）来自 CLI flag（任务属性，与服务端 DispatchCommand 顶层对称）。
     */
    private ExecutionContext.SparkSubmitRef readSparkRef(LocalRunArgs args) throws Exception {
        Map<String, String> m = (args.dsJsonPath() != null && !args.dsJsonPath().isBlank())
                ? parseFlatKv(Files.readString(Path.of(args.dsJsonPath()), StandardCharsets.UTF_8))
                : new LinkedHashMap<>();
        return new ExecutionContext.SparkSubmitRef(
                m.get("sparkHome"), m.get("master"), m.get("deployMode"),
                m.get("queue"), null, args.sparkMode(), args.jarPath(), args.mainClass());
    }

    /**
     * 合成本地 EngineSubmitRef：engineHome 优先从 ds-json 读（绑数据源时），
     * 否则从环境变量 *_HOME 取；内容形态（flinkMode/jarPath/mainClass）来自 CLI flag。
     */
    private ExecutionContext.EngineSubmitRef buildEngineRef(LocalRunArgs args) throws Exception {
        Map<String, String> m = (args.dsJsonPath() != null && !args.dsJsonPath().isBlank())
                ? parseFlatKv(Files.readString(Path.of(args.dsJsonPath()), StandardCharsets.UTF_8))
                : new LinkedHashMap<>();
        String engineHome = m.get("engineHome");
        if (engineHome == null || engineHome.isBlank()) {
            engineHome = System.getenv(args.type() + "_HOME");
        }
        return new ExecutionContext.EngineSubmitRef(
                args.type(), engineHome, args.flinkMode(),
                args.jarPath(), args.mainClass(), null, null,
                args.longRunning(), null);
    }

    /** 扁平 JSON "key":"value" → Map（ds-json 由 Go CLI 生成，扁平无嵌套；conf 等嵌套结构 MVP 不支持）。 */
    private static Map<String, String> parseFlatKv(String json) {
        Map<String, String> m = new LinkedHashMap<>();
        Matcher matcher = JSON_KV.matcher(json);
        while (matcher.find()) {
            m.put(matcher.group(1), unescape(matcher.group(2)));
        }
        return m;
    }

    private static String unescape(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\")
                .replace("\\n", "\n").replace("\\t", "\t").replace("\\r", "\r");
    }
}
