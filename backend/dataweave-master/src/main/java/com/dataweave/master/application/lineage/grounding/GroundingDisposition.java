package com.dataweave.master.application.lineage.grounding;

import com.dataweave.master.domain.lineage.Source;

/**
 * 一次候选表接地处置的内存结果（055，FR-005/FR-016），1:1 映射审计表 {@code lineage_grounding_disposition} 行。
 *
 * @param candidate     原始候选表名（规范化前）
 * @param direction     READS | WRITES
 * @param sourceChannel 来源通道（{@link Source}）；null 视为确定性
 * @param datasourceId  核验所用数据源 ID（null=未绑定）
 * @param verdict       PRESENT | ABSENT | UNKNOWN | SYSTEM_EXCLUDED
 * @param disposition   ADOPTED | DROPPED | EXCLUDED | RETAINED
 * @param reason        处置原因摘要（脱敏）
 */
public record GroundingDisposition(
        String candidate,
        String direction,
        Source sourceChannel,
        Long datasourceId,
        String verdict,
        String disposition,
        String reason
) {
    public static final String VERDICT_PRESENT = "PRESENT";
    public static final String VERDICT_ABSENT = "ABSENT";
    public static final String VERDICT_UNKNOWN = "UNKNOWN";
    public static final String VERDICT_SYSTEM_EXCLUDED = "SYSTEM_EXCLUDED";

    public static final String DISP_ADOPTED = "ADOPTED";
    public static final String DISP_DROPPED = "DROPPED";
    public static final String DISP_EXCLUDED = "EXCLUDED";
    public static final String DISP_RETAINED = "RETAINED";

    /** 审计表存来源枚举名；null → "NULL" 占位（既有 SQL 列级路径无 source）。 */
    public String sourceChannelName() {
        return sourceChannel != null ? sourceChannel.name() : "NULL";
    }
}
