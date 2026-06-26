package com.dataweave.master.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * Workspace 快照：前端多 tab 工作区状态的服务端持久化（替代原 agent_session.workspace_state）。
 * 一个客户端键（clientKey，前端 localStorage 自生成的不透明 id）对应一行;后端视 snapshotJson 为透明 blob。
 */
@Table("workspace_snapshot")
public class WorkspaceSnapshot {

    @Id
    private Long id;
    private Long tenantId;
    private String clientKey;     // 前端自生成的不透明键（原 conversationId），按它 UPSERT
    private String snapshotJson;  // Workspace 快照（前端序列化 JSON，透明 blob）
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public WorkspaceSnapshot() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public String getClientKey() {
        return clientKey;
    }

    public void setClientKey(String clientKey) {
        this.clientKey = clientKey;
    }

    public String getSnapshotJson() {
        return snapshotJson;
    }

    public void setSnapshotJson(String snapshotJson) {
        this.snapshotJson = snapshotJson;
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
}
