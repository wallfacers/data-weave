package com.dataweave.api.infrastructure;

import com.dataweave.master.application.DatasourceResolver;
import com.dataweave.master.application.DatasourceResolver.ResolvedConnection;
import com.dataweave.master.application.TaskExecutionGateway;
import com.dataweave.master.application.WorkerReportService;
import com.dataweave.master.domain.Datasource;
import com.dataweave.master.domain.DatasourceRepository;
import com.dataweave.master.domain.LogBus;
import com.dataweave.master.i18n.Messages;
import com.dataweave.worker.domain.CurrentExecution;
import com.dataweave.worker.domain.ExecutionContext;
import com.dataweave.worker.domain.TaskExecutor;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * {@link TaskExecutionGateway} 的进程内实现（all-in-one 默认）：异步执行任务并经 {@link WorkerReportService}
 * 回报，闭合「认领 → 下发 → 运行 → 回报 → 下游解锁」全链路。
 *
 * <p>由 {@code api} 模块承载（组合根），以引用 worker 模块的 {@link TaskExecutor} 实现。
 * distributed 模式由 WebClient 网关替代。
 *
 * <p>启动/收尾 banner（DataWorks 风）按触发者 locale 渲染（i18n 规则②）：locale 由
 * {@link DispatchCommand#locale()} 透传（落 {@code task_instance.locale}，调度认领时读出）；null 兜底 zh-CN。
 * 收尾 banner 含执行耗时，按 {@code ms/s/m/h} 可读换算（单位缩写跨语言一致）。
 */
@Component
@ConditionalOnProperty(name = "scheduler.mode", havingValue = "all-in-one", matchIfMissing = true)
public class InProcessTaskExecutionGateway implements TaskExecutionGateway {

    private static final Logger log = LoggerFactory.getLogger(InProcessTaskExecutionGateway.class);

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final WorkerReportService reportService;
    private final LogBus logBus;
    private final DatasourceRepository datasourceRepository;
    private final DatasourceResolver datasourceResolver;
    private final Messages messages;
    /** 按任务 type() 索引的执行器（注入的 Map 以 bean 名为键，这里改建按 type 的映射）。 */
    private final Map<String, TaskExecutor> byType;
    /** all-in-one 执行池：并发运行任务体的上限。原硬编码 max(2,核数)(本机=12) 会成为 all-in-one 下的执行并发瓶颈，
     *  改为可配（默认 64，对齐 dispatch-executor-threads），避免下发进来后卡在执行池排队。 */
    private final ExecutorService pool;

    public InProcessTaskExecutionGateway(WorkerReportService reportService,
                                         LogBus logBus,
                                         DatasourceRepository datasourceRepository,
                                         DatasourceResolver datasourceResolver,
                                         Messages messages,
                                         Map<String, TaskExecutor> executors,
                                         @org.springframework.beans.factory.annotation.Value(
                                                 "${scheduler.inproc-worker-threads:64}") int inprocWorkerThreads) {
        this.pool = Executors.newFixedThreadPool(
                Math.max(2, inprocWorkerThreads),
                r -> {
                    Thread t = new Thread(r, "inproc-worker");
                    t.setDaemon(true);
                    return t;
                });
        this.reportService = reportService;
        this.logBus = logBus;
        this.datasourceRepository = datasourceRepository;
        this.datasourceResolver = datasourceResolver;
        this.messages = messages;
        Map<String, TaskExecutor> m = new java.util.HashMap<>();
        for (TaskExecutor e : executors.values()) {
            if (e.type() != null) {
                m.put(e.type().toUpperCase(), e);
            }
        }
        this.byType = m;
    }

    @Override
    public void dispatch(DispatchCommand cmd) {
        pool.submit(() -> run(cmd));
    }

    private void run(DispatchCommand cmd) {
        Locale locale = cmd.locale() != null ? Locale.forLanguageTag(cmd.locale()) : Messages.DEFAULT_LOCALE;
        Instant startInstant = Instant.now();
        try {
            // fencing：DISPATCHED→RUNNING CAS 失败说明本实例已非当前派单（被 LeaseReaper 回收重派 / 已终态），
            // 立即中止，不执行任务体——堵住 isCurrentDispatch 放宽后残留的「回收窗口」双跑。
            if (!reportService.reportStarted(cmd.taskInstanceId())) {
                log.info("[InProcExec] 实例 {} attempt={} 非当前派单（reportStarted CAS 让步），中止执行",
                        cmd.taskInstanceId(), cmd.attempt());
                return;
            }

            // 逐行日志回调 → LogBus
            Consumer<String> lineConsumer = line -> logBus.append(cmd.taskInstanceId(), line);

            // 按任务 type 选执行器（默认 SHELL）
            String type = cmd.taskType() != null ? cmd.taskType().toUpperCase() : "SHELL";
            TaskExecutor executor = byType.get(type);

            // 数据源解析（通过 DatasourceResolver 按 taskType 输出不同格式）
            ResolvedConnection resolved = resolveDatasource(cmd.datasourceId(), type);
            ExecutionContext.DataSourceRef dsRef = resolved != null
                    ? new ExecutionContext.DataSourceRef(
                            resolved.name(), resolved.typeCode(), resolved.jdbcUrl(),
                            resolved.username(), resolved.password(),
                            resolved.driverJarId(), resolved.driverClass(), resolved.storageKey())
                    : null;

            // DataWorks 风启动 banner（按触发者 locale 渲染，i18n 规则②）
            emitStartBanner(lineConsumer, locale, cmd, type, dsRef);

            // 防御：注册类型外的未知 type 无执行器时模拟成功，闭合调度闭环（SQL/ECHO/SHELL 已各有执行器）。
            if (executor == null) {
                String msg = "type=" + type + " 无内置执行器，模拟执行成功";
                lineConsumer.accept(msg);
                emitEndBanner(lineConsumer, locale, true, 0, false, false, Duration.between(startInstant, Instant.now()));
                reportService.reportFinished(cmd.taskInstanceId(), 0, msg, List.of());
                return;
            }

            // 构建 ExecutionContext（含 Shell 环境变量 / Python 配置路径 / Spark 提交配置 / 通用引擎提交配置）
            Map<String, String> envVarMap = resolved != null ? resolved.shellEnvVars() : null;
            String pythonConfigPath = resolved != null ? resolved.pythonConfigPath() : null;
            ExecutionContext.SparkSubmitRef sparkRef = buildSparkRef(type, resolved, cmd);
            ExecutionContext.EngineSubmitRef engineRef = buildEngineRef(type, resolved, cmd);

            ExecutionContext ctx = new ExecutionContext(
                    cmd.content(),
                    cmd.bizDate(),
                    cmd.attempt(),
                    cmd.timeoutSeconds(),
                    cmd.runMode(),
                    type,
                    dsRef,
                    envVarMap,
                    pythonConfigPath,
                    sparkRef,
                    engineRef
            );

            // 062：绑定当前实例 id，供 FlinkTaskExecutor long_running detached 提交后回写
            // external_job_handle（in-process/all-in-one 下发路径此前漏绑，句柄不回写——TR 真跑暴露；
            // distributed 路径由 WorkerExecService.submit 绑定，两路径对齐）。
            CurrentExecution.bind(cmd.taskInstanceId());
            try {
                TaskExecutor.ExecutionResult result = executor.execute(ctx, lineConsumer);

                emitEndBanner(lineConsumer, locale, result.success(), result.exitCode(), result.timedOut(),
                        result.skipped(), Duration.between(startInstant, Instant.now()));

                // 尾部摘要（最后 4000 字符）写 task_instance.log
                String stdout = result.stdout();
                String tail = stdout.length() > 4000 ? stdout.substring(stdout.length() - 4000) : stdout;
                if (!result.stderr().isEmpty()) {
                    tail = tail + "\n[stderr] " + result.stderr();
                }

                if (result.skipped()) {
                    // SKIPPED（环境缺失）：按「非失败完成」处理，不阻塞下游、不报失败；
                    // 完整日志（含 start/end banner + 执行过程）一并写入，避免 LogBus 缺失时
                    // 前端仅看到 [SKIPPED] 摘要而丢失 banner（FR-008/009/012）。
                    // tail 已含 executor 输出的跳过原因 + end banner "Status: Skipped"，无需再加前缀。
                    reportService.reportFinished(cmd.taskInstanceId(), result.exitCode(), tail, List.of());
                } else if (result.success()) {
                    reportService.reportFinished(cmd.taskInstanceId(), result.exitCode(), tail,
                            result.statementMetrics());
                } else {
                    String reason = result.timedOut() ? "TIMEOUT" : "EXIT_CODE_" + result.exitCode();
                    reportService.reportFailed(cmd.taskInstanceId(), reason, tail);
                }
            } finally {
                CurrentExecution.clear();   // 062：清理 ThreadLocal 绑定，防线程池复用泄漏
                // 清理 Python 临时配置文件
                if (pythonConfigPath != null && datasourceResolver != null) {
                    datasourceResolver.cleanup(pythonConfigPath);
                }
            }
        } catch (Exception e) {
            log.warn("[InProcExec] 实例 {} 执行异常：{}", cmd.taskInstanceId(), e.getMessage());
            reportService.reportFailed(cmd.taskInstanceId(), "EXEC_ERROR", "执行异常：" + e.getMessage());
        }
    }

    /** 通过 DatasourceResolver 解析数据源连接配置；id 为空返回 null。 */
    private ResolvedConnection resolveDatasource(Long datasourceId, String taskType) {
        if (datasourceId == null || datasourceResolver == null) {
            return null;
        }
        try {
            return datasourceResolver.resolve(datasourceId, taskType);
        } catch (Exception e) {
            log.warn("[InProcExec] 数据源解析失败 (id={}): {}", datasourceId, e.getMessage());
            return null;
        }
    }

    /**
     * 合成 SparkSubmitRef：集群配置（sparkHome/master/...）来自 SPARK 数据源解析，
     * 内容形态（sparkMode/jarRef/mainClass）来自下发指令（任务定义 params）。
     * SPARK 任务即使未绑数据源也建 ref（sparkHome/master 为 null → 执行器判 SKIPPED，而非丢 sparkMode）。
     */
    private ExecutionContext.SparkSubmitRef buildSparkRef(String type, ResolvedConnection resolved,
                                                          DispatchCommand cmd) {
        if (!"SPARK".equals(type)) {
            return null;
        }
        ResolvedConnection.SparkClusterRef s = resolved != null ? resolved.spark() : null;
        Integer[] resources = parseResourceHints(cmd.resourcesJson());
        return new ExecutionContext.SparkSubmitRef(
                s != null ? s.sparkHome() : null,
                s != null ? s.master() : null,
                s != null ? s.deployMode() : null,
                s != null ? s.queue() : null,
                s != null ? s.conf() : null,
                cmd.sparkMode(), cmd.jarRef(), cmd.mainClass(),
                resources[0], resources[1]);  // 067：memoryMb/cpuCores 声明式提示
    }

    /**
     * 合成 EngineSubmitRef：集群/引擎配置来自 FLINK/DATAX/SEATUNNEL 数据源解析（EngineClusterRef），
     * 内容形态（engineMode/engineJarRef/engineMainClass）来自下发指令（任务定义 params）。
     * 仅 FLINK/DATAX/SEATUNNEL 类型建 ref；即使未绑数据源也建（engineHome 为 null → 执行器判 SKIPPED）。
     */
    private ExecutionContext.EngineSubmitRef buildEngineRef(String type, ResolvedConnection resolved,
                                                             DispatchCommand cmd) {
        if (!"FLINK".equals(type) && !"DATAX".equals(type) && !"SEATUNNEL".equals(type)) {
            return null;
        }
        ResolvedConnection.EngineClusterRef e = resolved != null ? resolved.engine() : null;
        Integer[] resources = parseResourceHints(cmd.resourcesJson());
        return new ExecutionContext.EngineSubmitRef(
                type,  // kind = task type
                e != null ? e.engineHome() : null,
                cmd.engineMode(),
                cmd.engineJarRef(),
                cmd.engineMainClass(),
                null,  // configPath 运行期填
                e != null ? e.props() : null,
                cmd.longRunning(),            // 062：detached 长驻分支
                cmd.externalJobHandle(),      // 062：reattach 句柄（非空则重连不重复提交）
                cmd.resumeSavepointPath(),    // D2：savepoint 恢复路径（优先于 reattach，全新提交 -s 恢复）
                resources[0], resources[1]);  // 067：memoryMb/cpuCores 声明式提示
    }

    /** 067：解析 resources_json（{"memoryMb":N,"cpuCores":N}）为 [memoryMb, cpuCores]；null/解析失败→[null,null]。 */
    private static Integer[] parseResourceHints(String resourcesJson) {
        if (resourcesJson == null || resourcesJson.isBlank()) {
            return new Integer[]{null, null};
        }
        try {
            var node = new tools.jackson.databind.ObjectMapper().readTree(resourcesJson);
            Integer memoryMb = node.hasNonNull("memoryMb") ? node.get("memoryMb").asInt() : null;
            Integer cpuCores = node.hasNonNull("cpuCores") ? node.get("cpuCores").asInt() : null;
            return new Integer[]{memoryMb, cpuCores};
        } catch (Exception e) {
            return new Integer[]{null, null};
        }
    }

    /** DataWorks 风启动 banner：运行模式 / 类型 / 数据源 / 开始时间（按触发者 locale 渲染）。 */
    private void emitStartBanner(Consumer<String> onLine, Locale locale, DispatchCommand cmd, String type,
                                 ExecutionContext.DataSourceRef ds) {
        String dsLabel = ds != null
                ? messages.get("taskrun.banner.start.datasource_fmt", locale, ds.name(), ds.typeCode())
                : messages.get("taskrun.banner.start.datasource_none", locale);
        onLine.accept("=========== " + messages.get("taskrun.banner.start.title", locale) + " ===========");
        onLine.accept(messages.get("taskrun.banner.start.line", locale,
                cmd.runMode() != null ? cmd.runMode() : "NORMAL", type, dsLabel));
        onLine.accept(messages.get("taskrun.banner.start.time", locale, LocalDateTime.now().format(TS)));
        onLine.accept("=========================================");
    }

    /** DataWorks 风收尾 banner：状态 / 退出码 / 执行耗时 / 结束时间（按触发者 locale 渲染）。
     *  skipped 优先于 timeout/success/failed（SKIPPED 按非失败完成、不阻塞下游，FR-012 不新增状态枚举）。 */
    private void emitEndBanner(Consumer<String> onLine, Locale locale, boolean success, int exitCode,
                               boolean timedOut, boolean skipped, Duration duration) {
        String statusKey;
        if (skipped) {
            statusKey = "taskrun.banner.status.skipped";
        } else if (timedOut) {
            statusKey = "taskrun.banner.status.timeout";
        } else {
            statusKey = success ? "taskrun.banner.status.success" : "taskrun.banner.status.failed";
        }
        onLine.accept("=========== " + messages.get("taskrun.banner.end.title", locale) + " ===========");
        onLine.accept(messages.get("taskrun.banner.end.line", locale,
                messages.get(statusKey, locale), exitCode));
        onLine.accept(messages.get("taskrun.banner.end.duration", locale, formatDuration(duration)));
        onLine.accept(messages.get("taskrun.banner.end.time", locale, LocalDateTime.now().format(TS)));
        onLine.accept("===============================");
    }

    /**
     * 执行耗时按可读单位换算（单位缩写跨语言一致）：
     * {@code <1s → "Xms"}；{@code <1min → "Xs"}；{@code <1h → "Xm Ys"}；否则 {@code "Xh Ym Zs"}。
     */
    private static String formatDuration(Duration d) {
        long totalSec = d.toSeconds();
        if (totalSec < 1) {
            return d.toMillis() + "ms";
        }
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        if (h > 0) {
            return h + "h " + m + "m " + s + "s";
        }
        if (m > 0) {
            return m + "m " + s + "s";
        }
        return s + "s";
    }

    @PreDestroy
    void shutdown() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
    }
}
