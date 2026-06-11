package com.dataweave.api.interfaces.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 心跳上报请求体 DTO。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class HeartbeatRequest {

    private String nodeCode;
    private String host;
    private String capacity;
    private Double cpu;
    private Double mem;
    private Double disk;
    private Double loadAvg;
    private Integer runningTasks;
    /** 进程启动纪元号（每次启动自增），master 据此检测 worker 重启。 */
    private Long incarnation;
    /** 当前运行中的任务实例 ID 列表（用于 master 侧租约续约）。 */
    private java.util.List<String> runningInstanceIds;

    public HeartbeatRequest() {
    }

    public String getNodeCode() {
        return nodeCode;
    }

    public void setNodeCode(String nodeCode) {
        this.nodeCode = nodeCode;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public String getCapacity() {
        return capacity;
    }

    public void setCapacity(String capacity) {
        this.capacity = capacity;
    }

    public Double getCpu() {
        return cpu;
    }

    public void setCpu(Double cpu) {
        this.cpu = cpu;
    }

    public Double getMem() {
        return mem;
    }

    public void setMem(Double mem) {
        this.mem = mem;
    }

    public Double getDisk() {
        return disk;
    }

    public void setDisk(Double disk) {
        this.disk = disk;
    }

    public Double getLoadAvg() {
        return loadAvg;
    }

    public void setLoadAvg(Double loadAvg) {
        this.loadAvg = loadAvg;
    }

    public Integer getRunningTasks() {
        return runningTasks;
    }

    public void setRunningTasks(Integer runningTasks) {
        this.runningTasks = runningTasks;
    }

    public Long getIncarnation() {
        return incarnation;
    }

    public void setIncarnation(Long incarnation) {
        this.incarnation = incarnation;
    }

    public java.util.List<String> getRunningInstanceIds() {
        return runningInstanceIds;
    }

    public void setRunningInstanceIds(java.util.List<String> runningInstanceIds) {
        this.runningInstanceIds = runningInstanceIds;
    }
}
