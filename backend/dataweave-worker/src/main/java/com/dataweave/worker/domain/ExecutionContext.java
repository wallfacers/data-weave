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
 * @param taskType        任务类型（SQL / SHELL / ECHO），用于启动日志标注
 * @param datasource      业务数据源连接信息（SQL 执行用）；null=未配置/不可用，SQL 执行端回退模拟
 * @param shellEnvVars    Shell 任务数据源环境变量（DW_DS_* 系列）；null=无数据源注入
 * @param pythonConfigPath Python 任务数据源 JSON 配置文件路径；null=无数据源注入
 */
public record ExecutionContext(String content, String bizDate, int attempt, int timeoutSeconds,
                               String runMode, String taskType, DataSourceRef datasource,
                               Map<String, String> shellEnvVars, String pythonConfigPath) {

    /** 向后兼容的精简构造（无数据源/模式信息，SHELL/ECHO 用）。 */
    public ExecutionContext(String content, String bizDate, int attempt, int timeoutSeconds) {
        this(content, bizDate, attempt, timeoutSeconds, null, null, null, null, null);
    }

    /** 向后兼容构造（有数据源但无 Shell/Python 注入）。 */
    public ExecutionContext(String content, String bizDate, int attempt, int timeoutSeconds,
                           String runMode, String taskType, DataSourceRef datasource) {
        this(content, bizDate, attempt, timeoutSeconds, runMode, taskType, datasource, null, null);
    }

    /**
     * 业务数据源连接引用（已解析，不含未解密密文）。
     *
     * @param name     数据源名（启动日志展示）
     * @param typeCode 类型编码（MYSQL/POSTGRESQL/…，启动日志展示）
     * @param jdbcUrl  JDBC URL
     * @param username 用户名
     * @param password 口令（已解密明文）
     */
    public record DataSourceRef(String name, String typeCode, String jdbcUrl,
                                String username, String password) {
    }
}
