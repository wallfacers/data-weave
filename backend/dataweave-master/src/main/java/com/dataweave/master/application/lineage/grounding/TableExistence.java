package com.dataweave.master.application.lineage.grounding;

/**
 * 候选表在数据源目录中的三态存在性（055 目录接地，FR-003）。
 *
 * <p>严格区分 {@link #ABSENT} 与 {@link #UNKNOWN} 是本特性全部正确性所系：
 * 绝不把"查不到"当"确认缺席"，否则会误杀真表（SC-003 误杀率 0 的地基）。
 */
public enum TableExistence {
    /** 目录（缓存/neo4j/live probe）确认表存在。 */
    PRESENT,
    /** 数据源可达且成功查询、目录中权威确认无此表。 */
    ABSENT,
    /** 无法查证（未绑定数据源 / 连接失败 / 超时 / 解密失败 / 非 JDBC / 名称规范化失败）。 */
    UNKNOWN
}
