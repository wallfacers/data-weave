package com.dataweave.worker.domain;

import java.util.Map;

/**
 * 任务执行上下文：TaskExecutor 所需的运行参数。
 *
 * <p>由调用方从 {@code DispatchCommand} 构造，传入执行器。worker 据此启动进程、
 * 注入环境变量（{@code DW_BIZ_DATE} / {@code DW_ATTEMPT}）、或连业务数据源执行 SQL。
 *
 * @param content         执行内容（shell 命令/脚本 / SQL）
 * @param bizDate         业务日期（注入环境变量 {@code DW_BIZ_DATE}），可为 null
 * @param attempt         本次尝试序号（注入环境变量 {@code DW_ATTEMPT}）
 * @param timeoutSeconds  超时秒数（≤ 0 表示不限时）
 * @param runMode         运行模式（NORMAL / TEST），用于启动日志标注
 * @param taskType        任务类型（SQL / SHELL / ECHO / SPARK），用于启动日志标注
 * @param datasource      业务数据源连接信息（SQL 执行用）；null=未配置/不可用，SQL 执行端判 SKIPPED
 * @param shellEnvVars    Shell 任务数据源环境变量（DW_DS_* 系列）；null=无数据源注入
 * @param pythonConfigPath Python 任务数据源 JSON 配置文件路径；null=无数据源注入
 * @param spark           Spark 提交配置（SPARK 任务用）；null=非 SPARK 任务或未配置，执行器侧判 SKIPPED
 * @param engine          通用引擎提交配置（FLINK/DATAX/SEATUNNEL 共用）；null=非引擎任务或未配置
 */
public record ExecutionContext(String content, String bizDate, int attempt, int timeoutSeconds,
                               String runMode, String taskType, DataSourceRef datasource,
                               Map<String, String> shellEnvVars, String pythonConfigPath,
                               SparkSubmitRef spark, EngineSubmitRef engine) {

    /** 向后兼容的精简构造（无数据源/模式信息，SHELL/ECHO 用）。 */
    public ExecutionContext(String content, String bizDate, int attempt, int timeoutSeconds) {
        this(content, bizDate, attempt, timeoutSeconds, null, null, null, null, null, null, null);
    }

    /** 向后兼容构造（有数据源但无 Shell/Python/Spark/Engine 注入）。 */
    public ExecutionContext(String content, String bizDate, int attempt, int timeoutSeconds,
                           String runMode, String taskType, DataSourceRef datasource) {
        this(content, bizDate, attempt, timeoutSeconds, runMode, taskType, datasource, null, null, null, null);
    }

    /** 向后兼容构造（无 Spark/Engine 注入，老调用点 9 参 → spark=null, engine=null）。 */
    public ExecutionContext(String content, String bizDate, int attempt, int timeoutSeconds,
                           String runMode, String taskType, DataSourceRef datasource,
                           Map<String, String> shellEnvVars, String pythonConfigPath) {
        this(content, bizDate, attempt, timeoutSeconds, runMode, taskType, datasource,
                shellEnvVars, pythonConfigPath, null, null);
    }

    /** 向后兼容构造（含 Spark 但无 Engine 注入，老调用点 10 参全参 → engine=null）。 */
    public ExecutionContext(String content, String bizDate, int attempt, int timeoutSeconds,
                           String runMode, String taskType, DataSourceRef datasource,
                           Map<String, String> shellEnvVars, String pythonConfigPath,
                           SparkSubmitRef spark) {
        this(content, bizDate, attempt, timeoutSeconds, runMode, taskType, datasource,
                shellEnvVars, pythonConfigPath, spark, null);
    }

    /**
     * 业务数据源连接引用（已解析，不含未解密密文）。
     *
     * @param name        数据源名（启动日志展示）
     * @param typeCode    类型编码（MYSQL/POSTGRESQL/…，启动日志展示）
     * @param jdbcUrl     JDBC URL
     * @param username    用户名
     * @param password    口令（已解密明文）
     * @param driverJarId 绑定的上传驱动 jar 资产 id（datasource-driver-isolation）；null=走内置默认驱动
     * @param driverClass 上传 jar 的驱动类名（隔离加载用）；null=内置
     * @param storageKey  上传 jar 的存储 key（worker 据此从存储后端取 jar 隔离加载）；null=内置
     */
    public record DataSourceRef(String name, String typeCode, String jdbcUrl,
                                String username, String password,
                                Long driverJarId, String driverClass, String storageKey) {
        /** 向后兼容：无上传驱动 jar（走内置默认驱动）。 */
        public DataSourceRef(String name, String typeCode, String jdbcUrl,
                             String username, String password) {
            this(name, typeCode, jdbcUrl, username, password, null, null, null);
        }
    }

    /**
     * Spark 提交配置引用（SPARK 数据源解析 + 任务声明 sparkMode/jar 合成）。
     *
     * @param sparkHome  SPARK_HOME；空 / {@code ${sparkHome}/bin/spark-submit} 不存在 → SKIPPED
     * @param master     local[*] | yarn | spark://... ；空 → SKIPPED
     * @param deployMode client | cluster（可空，默认 client）
     * @param queue      yarn 队列（可空）
     * @param conf       附加 spark.* 配置项（可空）
     * @param sparkMode  pyspark | spark-sql | jar（内容形态判别，FR-002）
     * @param jarPath    jar 形态的 application jar 路径（其它形态 null）
     * @param mainClass  jar 形态的 --class 主类（其它形态 null）
     */
    public record SparkSubmitRef(String sparkHome, String master, String deployMode, String queue,
                                 Map<String, String> conf, String sparkMode, String jarPath,
                                 String mainClass) {
    }

    /**
     * 通用引擎提交配置引用（FLINK/DATAX/SEATUNNEL 共用，data-model §3）。
     *
     * @param kind       引擎类型：FLINK | DATAX | SEATUNNEL
     * @param engineHome 引擎 home 路径（FLINK_HOME / DATAX_HOME / SEATUNNEL_HOME）
     * @param mode       子模式：Flink sql|jar；DataX/SeaTunnel null
     * @param jarPath    Flink jar 形态的 application jar 路径（其它形态 null）
     * @param mainClass  Flink jar 形态的 --class 主类（其它形态 null）
     * @param configPath 执行器写入的临时作业/配置文件路径（运行期填，构造期 null）
     * @param props      集群/引擎附加配置（jobmanager/parallelism 等，可空）
     * @param longRunning 外部托管长驻作业标记（Flink 流式=true；有界/批=false）—— 060 节点容错闭环
     * @param externalJobHandle 已持久化的外部作业句柄（reattach 用；null=首次提交）—— 060 节点容错闭环
     */
    public record EngineSubmitRef(String kind, String engineHome, String mode, String jarPath,
                                   String mainClass, String configPath, Map<String, String> props,
                                   boolean longRunning, String externalJobHandle,
                                   String savepointRestorePath) {
        /** 向后兼容构造（无 long_running/external_job_handle/savepoint 字段，默认 false/null/null）。 */
        public EngineSubmitRef(String kind, String engineHome, String mode, String jarPath,
                              String mainClass, String configPath, Map<String, String> props) {
            this(kind, engineHome, mode, jarPath, mainClass, configPath, props, false, null, null);
        }

        /** 062 向后兼容构造（含 long_running/external_job_handle，无 savepoint 恢复路径）。 */
        public EngineSubmitRef(String kind, String engineHome, String mode, String jarPath,
                              String mainClass, String configPath, Map<String, String> props,
                              boolean longRunning, String externalJobHandle) {
            this(kind, engineHome, mode, jarPath, mainClass, configPath, props,
                    longRunning, externalJobHandle, null);
        }
    }
}
