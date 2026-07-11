package com.dataweave.master.application;

import com.dataweave.master.domain.Checkpoint;
import com.dataweave.master.infrastructure.CheckpointRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 062 检查点服务（US3 写 + 滚动保留）。写成功检查点后滚动淘汰——每个实例只保留最近 N 个 SUCCESS
 * （默认 3，可配 streaming.checkpoint.retention-count），更早的标记 EXPIRED（软淘汰，供审计追溯）。
 */
@Service
public class CheckpointService {

    private final CheckpointRepository repo;
    private final int retentionCount;

    public CheckpointService(CheckpointRepository repo,
                             @Value("${streaming.checkpoint.retention-count:3}") int retentionCount) {
        this.repo = repo;
        this.retentionCount = Math.max(1, retentionCount);
    }

    /**
     * 记录一个成功检查点（stop-with-savepoint 完成后调用），随即滚动淘汰超出 N 的更早检查点。
     *
     * @return 新写入检查点的 id
     */
    public UUID recordSuccess(UUID instanceId, String checkpointPath, String externalRef, Long sizeBytes) {
        int ordinal = repo.nextOrdinal(instanceId);
        UUID id = repo.insert(instanceId, ordinal, checkpointPath, externalRef,
                Checkpoint.SUCCESS, sizeBytes, LocalDateTime.now());
        // 滚动淘汰：仅保留最近 N 个 SUCCESS，更早的 → EXPIRED
        repo.expireBeyond(instanceId, retentionCount);
        return id;
    }
}
