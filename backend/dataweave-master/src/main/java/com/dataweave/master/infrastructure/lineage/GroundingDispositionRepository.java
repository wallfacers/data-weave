package com.dataweave.master.infrastructure.lineage;

import com.dataweave.master.application.lineage.grounding.GroundingDisposition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 055 {@code lineage_grounding_disposition} 的 JdbcTemplate 仓储（契约 grounding-stage.md C2）。
 *
 * <p>被 {@code ABSENT} 剔除的候选无边可挂元数据 → 独立落库；DROPPED/EXCLUDED/ADOPTED 必留痕，
 * 供人工追溯任一血缘边为何被保留/剔除（FR-016/SC-006）。
 */
@Repository
public class GroundingDispositionRepository {

    private final JdbcTemplate jdbc;

    public GroundingDispositionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long tenantId, long projectId, Long taskDefId,
                       String candidate, String direction, String sourceChannel,
                       Long datasourceId, String verdict, String disposition, String reason) {
        jdbc.update(
                "INSERT INTO lineage_grounding_disposition (tenant_id, project_id, task_def_id, candidate, " +
                "    direction, source_channel, datasource_id, verdict, disposition, reason) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?)",
                tenantId, projectId, taskDefId, candidate, direction, sourceChannel,
                datasourceId, verdict, disposition, reason);
    }

    /** 便捷重载：直接落一条 {@link GroundingDisposition}。 */
    public void insert(long tenantId, long projectId, Long taskDefId, GroundingDisposition d) {
        insert(tenantId, projectId, taskDefId, d.candidate(), d.direction(), d.sourceChannelName(),
                d.datasourceId(), d.verdict(), d.disposition(), d.reason());
    }
}
