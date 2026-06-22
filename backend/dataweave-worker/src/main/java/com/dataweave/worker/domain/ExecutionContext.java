package com.dataweave.worker.domain;

/**
 * 任务执行上下文：TaskExecutor 所需的运行参数。
 *
 * <p>由调用方从 {@code DispatchCommand} 构造，传入执行器。worker 据此启动进程、
 * 注入环境变量（{@code DW_BIZ_DATE} / {@code DW_ATTEMPT}）、或连业务数据源执行 SQL。
 *
 * @param content        执行内容（shell 命令/脚本 / SQL）
 * @param bizDate        业务日期（注入环境变量 {@code DW_BIZ_DATE}），可为 null
 * @param attempt        本次尝试序号（注入环境变量 {@code DW_ATTEMPT}）
 * @param timeoutSeconds 超时秒数（≤ 0 表示不限时）
 * @param runMode        运行模式（NORMAL / TEST），用于启动日志标注
 * @param taskType       任务类型（SQL / SHELL / ECHO），用于启动日志标注
 * @param datasource     业务数据源连接信息（SQL 执行用）；null=未配置/不可用，SQL 执行端回退模拟
 */
public record ExecutionContext(String content, String bizDate, int attempt, int timeoutSeconds,
                               String runMode, String taskType, DataSourceRef datasource) {

    /** 向后兼容的精简构造（无数据源/模式信息，SHELL/ECHO 用）。 */
    public ExecutionContext(String content, String bizDate, int attempt, int timeoutSeconds) {
        this(content, bizDate, attempt, timeoutSeconds, null, null, null);
    }

    /**
     * 业务数据源连接引用（已解析，不含未解密密文）。
     *
     * @param name     数据源名（启动日志展示）
     * @param typeCode 类型编码（MYSQL/POSTGRESQL/…，启动日志展示）
     * @param jdbcUrl  JDBC URL
     * @param username 用户名
     * @param password 口令（当前实现取 password_enc 列原值；无独立解密体系）
     */
    public record DataSourceRef(String name, String typeCode, String jdbcUrl,
                                String username, String password) {
    }
}
