package com.dataweave.master.application;

import com.dataweave.master.domain.EventBus;
import com.dataweave.master.domain.InstanceStates;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 任务流失败恢复（design D8，task 2.9）。两种入口共享同一套「重置节点 → 工作流回 RUNNING → 唤醒调度」机制：
 * <ul>
 *   <li><b>断点恢复</b> {@link #resume}：保留 SUCCESS 节点终态跳过，仅把失败/未跑/被取消的节点重置 WAITING，
 *       从失败点续跑整条流（FAILED → RUNNING 再入）。</li>
 *   <li><b>整流重跑</b> {@link #rerunAll}：重置全部节点（含 SUCCESS）后走同一恢复机制。</li>
 * </ul>
 * 重置清空 worker/租约/attempt/失败归因；DAG 下游解锁由调度内核的可运行门自然驱动。
 * 调用方经 {@link GatedActionService} 闸门留痕（见 DefaultPlatformActionExecutor 接线）。
 */
@Service
public class RecoveryService {

    private final JdbcTemplate jdbc;
    private final EventBus eventBus;

    public RecoveryService(JdbcTemplate jdbc, EventBus eventBus) {
        this.jdbc = jdbc;
        this.eventBus = eventBus;
    }

    /** 断点恢复：FAILED 工作流 → RUNNING，非 SUCCESS 节点重置 WAITING。返回是否生效。 */
    public boolean resume(UUID workflowInstanceId) {
        LocalDateTime now = LocalDateTime.now();
        int w = jdbc.update(
                "UPDATE workflow_instance SET state='RUNNING', finished_at=NULL, updated_at=? "
                        + "WHERE id=? AND state='FAILED' AND deleted=0",
                now, workflowInstanceId);
        if (w == 0) {
            return false;  // 非失败工作流，不可断点恢复
        }
        resetNodes(workflowInstanceId, false, now);
        wake();
        return true;
    }

    /** 整流重跑：工作流（任意终态）→ RUNNING，全部节点重置 WAITING。返回是否生效。 */
    public boolean rerunAll(UUID workflowInstanceId) {
        LocalDateTime now = LocalDateTime.now();
        int w = jdbc.update(
                "UPDATE workflow_instance SET state='RUNNING', finished_at=NULL, updated_at=? "
                        + "WHERE id=? AND deleted=0",
                now, workflowInstanceId);
        if (w == 0) {
            return false;
        }
        resetNodes(workflowInstanceId, true, now);
        wake();
        return true;
    }

    private void resetNodes(UUID workflowInstanceId, boolean includeSuccess, LocalDateTime now) {
        String guard = includeSuccess ? "" : " AND state<>'" + InstanceStates.SUCCESS + "'";
        jdbc.update(
                "UPDATE task_instance SET state='WAITING', attempt=0, worker_node_code=NULL, "
                        + "lease_expire_at=NULL, failure_reason=NULL, finished_at=NULL, exit_code=NULL, "
                        + "started_at=NULL, updated_at=? WHERE workflow_instance_id=? AND run_mode='NORMAL' "
                        + "AND deleted=0" + guard,
                now, workflowInstanceId);
    }

    private void wake() {
        eventBus.publish(InstanceStates.WAKE_CHANNEL, "recovery");
    }
}
