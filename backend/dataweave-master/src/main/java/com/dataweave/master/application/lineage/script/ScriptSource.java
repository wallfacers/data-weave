package com.dataweave.master.application.lineage.script;

/**
 * 脚本血缘抽取输入（041）。
 *
 * <p>content 为任务脚本源码（task_def.content，≤4000 字符）；datasource 坐标沿任务绑定
 * （读侧 datasourceId / 写侧 targetDatasourceId，FR-011），坐标解析在编排层做，抽取器只见文本。
 */
public record ScriptSource(
        long tenantId,
        long projectId,
        Long taskDefId,
        String taskType,
        String content,
        Long datasourceId,
        Long targetDatasourceId
) {}
