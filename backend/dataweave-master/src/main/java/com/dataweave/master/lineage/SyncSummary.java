package com.dataweave.master.lineage;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 运行态同步摘要 —— 今日同步行数聚合。
 *
 * <p>{@code syncedRows} 为 {@code null} 时表示无采集，前端显示"估算中"。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SyncSummary(
        /** 今日同步行数聚合（null = 无采集）。 */
        Long syncedRows) {

    public static SyncSummary empty() {
        return new SyncSummary(null);
    }
}
