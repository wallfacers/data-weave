package com.dataweave.master.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("worker_nodes")
public class WorkerNode {

    @Id
    private Long id;
    private String nodeCode;
    private String host;
    private String ip;
    private String capacity;
    private Double cpu;
    private Double mem;
    private Double disk;
    private Double loadAvg;
    private Integer runningTasks;
    private String status;
    private Integer maxConcurrentTasks;
    private String nodeGroup;
    private Long incarnation;
    private Integer reservedTestSlots;
    // 060 节点容错闭环：节点健康三列（原子/单调更新；incarnation_since=FleetService 在纪元变化/新注册时落 now）
    private LocalDateTime incarnationSince;
    private Integer consecutiveInfraFailures;
    private LocalDateTime quarantinedUntil;
    private LocalDateTime lastHeartbeat;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer deleted;
    private Integer version;

    public WorkerNode() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getMaxConcurrentTasks() {
        return maxConcurrentTasks;
    }

    public void setMaxConcurrentTasks(Integer maxConcurrentTasks) {
        this.maxConcurrentTasks = maxConcurrentTasks;
    }

    public String getNodeGroup() {
        return nodeGroup;
    }

    public void setNodeGroup(String nodeGroup) {
        this.nodeGroup = nodeGroup;
    }

    public Long getIncarnation() {
        return incarnation;
    }

    public void setIncarnation(Long incarnation) {
        this.incarnation = incarnation;
    }

    public Integer getReservedTestSlots() {
        return reservedTestSlots;
    }

    public void setReservedTestSlots(Integer reservedTestSlots) {
        this.reservedTestSlots = reservedTestSlots;
    }

    /** 当前 incarnation 首次观察时刻（060 稳定窗判据）。 */
    public LocalDateTime getIncarnationSince() {
        return incarnationSince;
    }

    public void setIncarnationSince(LocalDateTime incarnationSince) {
        this.incarnationSince = incarnationSince;
    }

    /** 近期连续 infra 故障计数（060 熔断）。 */
    public Integer getConsecutiveInfraFailures() {
        return consecutiveInfraFailures;
    }

    public void setConsecutiveInfraFailures(Integer consecutiveInfraFailures) {
        this.consecutiveInfraFailures = consecutiveInfraFailures;
    }

    /** 熔断隔离到期时刻（060，GREATEST 只增不减）。 */
    public LocalDateTime getQuarantinedUntil() {
        return quarantinedUntil;
    }

    public void setQuarantinedUntil(LocalDateTime quarantinedUntil) {
        this.quarantinedUntil = quarantinedUntil;
    }

    public LocalDateTime getLastHeartbeat() {
        return lastHeartbeat;
    }

    public void setLastHeartbeat(LocalDateTime lastHeartbeat) {
        this.lastHeartbeat = lastHeartbeat;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public Long getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(Long updatedBy) {
        this.updatedBy = updatedBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Integer getDeleted() {
        return deleted;
    }

    public void setDeleted(Integer deleted) {
        this.deleted = deleted;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }
}
