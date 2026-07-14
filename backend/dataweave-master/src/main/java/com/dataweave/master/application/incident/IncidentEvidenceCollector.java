package com.dataweave.master.application.incident;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.dataweave.master.domain.TaskDef;
import com.dataweave.master.domain.TaskDefRepository;
import com.dataweave.master.domain.TaskInstance;
import com.dataweave.master.domain.TaskInstanceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 067 诊断证据采集（US1/FR-002）：失败实例日志尾部 + 任务定义 + 近期运行历史 + 实时任务外部句柄/检查点可用性。
 * 全部证据源复用既有读取口（task_instance.log 已持久化全量日志、task_def、task_checkpoint），零新采集设施。
 */
@Component
public class IncidentEvidenceCollector {

    private static final int HARD_BUDGET_CHARS = 32_000;

    private final JdbcTemplate jdbc;
    private final TaskInstanceRepository instanceRepo;
    private final TaskDefRepository taskDefRepo;
    private final int tailLines;

    public IncidentEvidenceCollector(JdbcTemplate jdbc, TaskInstanceRepository instanceRepo,
                                      TaskDefRepository taskDefRepo,
                                      @Value("${ops.incident.evidence-log-tail-lines:400}") int tailLines) {
        this.jdbc = jdbc;
        this.instanceRepo = instanceRepo;
        this.taskDefRepo = taskDefRepo;
        this.tailLines = tailLines;
    }

    public record HistoryEntry(UUID id, String state, Integer exitCode, LocalDateTime startedAt, LocalDateTime finishedAt) {
    }

    /** 一次失败的完整诊断证据包。taskDef 可能为 null（任务定义已被删除）。 */
    public record Evidence(TaskInstance failedInstance, TaskDef taskDef, String logTail,
                            List<HistoryEntry> history, boolean streaming, String externalJobHandle,
                            boolean hasAvailableCheckpoint) {
    }

    public Evidence collect(UUID instanceId) {
        TaskInstance ti = instanceRepo.findById(instanceId)
                .orElseThrow(() -> new IllegalArgumentException("task_instance not found: " + instanceId));
        TaskDef td = ti.getTaskId() != null ? taskDefRepo.findById(ti.getTaskId()).orElse(null) : null;
        String logTail = tailOf(ti.getLog());
        List<HistoryEntry> history = ti.getTaskId() != null
                ? queryHistory(ti.getTaskId(), instanceId, 10) : List.of();
        boolean streaming = Boolean.TRUE.equals(ti.getLongRunning());
        boolean hasCheckpoint = streaming && hasAvailableCheckpoint(instanceId);
        return new Evidence(ti, td, logTail, history, streaming, ti.getExternalJobHandle(), hasCheckpoint);
    }

    /** 超长日志头尾采样截断，预算 ~32KB（防止日志过大拖垮诊断或无限消耗 token）。 */
    private String tailOf(String log) {
        if (log == null || log.isEmpty()) return "";
        String[] lines = log.split("\n", -1);
        String result;
        if (lines.length <= tailLines) {
            result = log;
        } else {
            int headKeep = Math.min(20, tailLines / 10);
            int tailKeep = tailLines - headKeep;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < headKeep; i++) {
                sb.append(lines[i]).append('\n');
            }
            sb.append("... (truncated ").append(lines.length - tailLines).append(" lines) ...\n");
            for (int i = lines.length - tailKeep; i < lines.length; i++) {
                sb.append(lines[i]).append('\n');
            }
            result = sb.toString();
        }
        if (result.length() > HARD_BUDGET_CHARS) {
            result = result.substring(result.length() - HARD_BUDGET_CHARS);
        }
        return result;
    }

    private List<HistoryEntry> queryHistory(Long taskId, UUID excludeInstanceId, int limit) {
        return jdbc.query(
                "SELECT id, state, exit_code, started_at, finished_at FROM task_instance " +
                "WHERE task_id = ? AND id <> ? ORDER BY id DESC LIMIT ?",
                (rs, n) -> new HistoryEntry(
                        (UUID) rs.getObject("id"),
                        rs.getString("state"),
                        rs.getObject("exit_code", Integer.class),
                        rs.getObject("started_at", LocalDateTime.class),
                        rs.getObject("finished_at", LocalDateTime.class)),
                taskId, excludeInstanceId, limit);
    }

    private boolean hasAvailableCheckpoint(UUID instanceId) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_checkpoint WHERE task_instance_id = ? AND status = 'SUCCESS'",
                Integer.class, instanceId);
        return c != null && c > 0;
    }
}
