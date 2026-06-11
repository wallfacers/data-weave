package com.dataweave.api.interfaces.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Worker 任务状态回报请求体 DTO。
 *
 * <p>由 worker 进程通过 HTTP POST 发到任一 master 的 /api/cluster/report 端点。
 * 共享 token 鉴权（cluster.auth.token）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TaskReportRequest {

    /** started / finished / failed */
    private String event;

    /** 任务实例 ID */
    private String taskInstanceId;

    /** 退出码（finished 时有效） */
    private Integer exitCode;

    /** 失败原因（failed 时有效） */
    private String failureReason;

    /** 尾部日志摘要 */
    private String tailLog;

    public TaskReportRequest() {
    }

    public String getEvent() {
        return event;
    }

    public void setEvent(String event) {
        this.event = event;
    }

    public String getTaskInstanceId() {
        return taskInstanceId;
    }

    public void setTaskInstanceId(String taskInstanceId) {
        this.taskInstanceId = taskInstanceId;
    }

    public Integer getExitCode() {
        return exitCode;
    }

    public void setExitCode(Integer exitCode) {
        this.exitCode = exitCode;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public String getTailLog() {
        return tailLog;
    }

    public void setTailLog(String tailLog) {
        this.tailLog = tailLog;
    }
}
