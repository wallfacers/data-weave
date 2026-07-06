package com.dataweave.master.application.readiness;

import com.dataweave.master.infrastructure.ReadinessSignalRepository;
import com.dataweave.master.infrastructure.ReadinessSignalRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 051 就绪态物化：在完成/reset 事务内 append 一条 readiness_signal（no-loss 构造）。
 *
 * <p>单行写，不做下游扇出写（守不变量③④：完成事务不触碰下游行）。
 * <p>由 WorkerReportService 在 casTaskTerminal 同事务内调用。
 */
@Service
public class ReadinessSignalWriter {

    private static final Logger log = LoggerFactory.getLogger(ReadinessSignalWriter.class);

    private final ReadinessSignalRepository repo;

    public ReadinessSignalWriter(ReadinessSignalRepository repo) {
        this.repo = repo;
    }

    /**
     * 写入 TERMINAL 信号（上游实例到达放行终态：SUCCESS/FAILED）。
     *
     * @param tenantId             租户
     * @param projectId            项目
     * @param upstreamInstanceId   到达终态的实例 id
     * @param workflowId           工作流 id（跨周期反查用）
     * @param workflowInstanceId   工作流实例 id（同 DAG 后继定位用）
     * @param workflowNodeId       节点 id（edge/dependency 反查的 from/depend 端）
     * @param bizDate              业务日期（跨周期逆偏移用）
     */
    public long writeTerminal(long tenantId, long projectId, UUID upstreamInstanceId,
                               Long workflowId, UUID workflowInstanceId,
                               Long workflowNodeId, String bizDate) {
        ReadinessSignalRow row = ReadinessSignalRow.terminal(tenantId, projectId,
                upstreamInstanceId, workflowId, workflowInstanceId, workflowNodeId, bizDate);
        long id = repo.insert(row);
        log.debug("[SignalWriter] TERMINAL id={} upstream={}", id, upstreamInstanceId);
        return id;
    }

    /**
     * 写入 RESET 信号（上游实例被 rerun/reset，不再处于放行终态）。
     */
    public long writeReset(long tenantId, long projectId, UUID upstreamInstanceId,
                            Long workflowId, UUID workflowInstanceId,
                            Long workflowNodeId, String bizDate) {
        ReadinessSignalRow row = ReadinessSignalRow.reset(tenantId, projectId,
                upstreamInstanceId, workflowId, workflowInstanceId, workflowNodeId, bizDate);
        long id = repo.insert(row);
        log.debug("[SignalWriter] RESET id={} upstream={}", id, upstreamInstanceId);
        return id;
    }
}
