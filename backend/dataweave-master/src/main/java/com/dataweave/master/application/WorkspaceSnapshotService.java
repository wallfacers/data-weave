package com.dataweave.master.application;

import com.dataweave.master.domain.WorkspaceSnapshot;
import com.dataweave.master.domain.WorkspaceSnapshotRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Workspace 快照存取服务（workspace-persistence）。按客户端键（前端自生成的不透明 id）UPSERT，
 * 后端不解析 snapshot 语义。替代原 AgentAuditService 的 workspace_state 读写（服务端 AI 拆除后）。
 */
@Service
public class WorkspaceSnapshotService {

    private final WorkspaceSnapshotRepository repository;

    public WorkspaceSnapshotService(WorkspaceSnapshotRepository repository) {
        this.repository = repository;
    }

    /** 读 Workspace 快照：无记录或为空返回 empty。 */
    public Optional<String> getWorkspaceState(String clientKey) {
        return repository.findFirstByClientKeyOrderByIdDesc(clientKey)
                .map(WorkspaceSnapshot::getSnapshotJson)
                .filter(s -> s != null && !s.isBlank());
    }

    /** 写 Workspace 快照：按 clientKey UPSERT。 */
    public void putWorkspaceState(String clientKey, String snapshotJson) {
        LocalDateTime now = LocalDateTime.now();
        WorkspaceSnapshot snapshot = repository.findFirstByClientKeyOrderByIdDesc(clientKey)
                .orElseGet(() -> {
                    WorkspaceSnapshot s = new WorkspaceSnapshot();
                    s.setTenantId(1L);
                    s.setClientKey(clientKey);
                    s.setCreatedAt(now);
                    return s;
                });
        snapshot.setSnapshotJson(snapshotJson);
        snapshot.setUpdatedAt(now);
        repository.save(snapshot);
    }
}
