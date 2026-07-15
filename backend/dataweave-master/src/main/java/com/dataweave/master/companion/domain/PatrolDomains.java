package com.dataweave.master.companion.domain;

/**
 * 巡检领域（FR-006）。四领域独立例程，可单独启停。DB 列 patrol_routine.domain 存这些常量。
 *
 * <p>不要用 Java enum——与 incident 等既有域一致，用 String 常量 + DB VARCHAR/CHECK，
 * RowMapper 直接 {@code rs.getString}，枚举演进免迁移。
 */
public final class PatrolDomains {

    public static final String TASK_FAILURE = "TASK_FAILURE";   // 任务失败（最敏感，seed 15min）
    public static final String MACHINE = "MACHINE";             // 机器状态（seed 30min）
    public static final String DATA_QUALITY = "DATA_QUALITY";   // 数据质量（seed 60min）
    public static final String CODE_QUALITY = "CODE_QUALITY";   // 代码质量（seed 每日 02:00）

    private PatrolDomains() {}
}
