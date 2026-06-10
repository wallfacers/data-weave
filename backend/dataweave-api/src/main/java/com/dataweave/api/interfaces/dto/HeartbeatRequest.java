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
}
