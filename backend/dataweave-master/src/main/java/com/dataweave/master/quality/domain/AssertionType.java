package com.dataweave.master.quality.domain;

/**
 * 质量断言类型（8 类，FR-001）。每类经 {@code QualityRuleCompiler}（application 层）
 * 编译为度量 SQL，由 {@code QualityProbeExecutor} 读回标量 measured_value 后与期望比较（research D6）。
 *
 * <p>SCHEMA 经 JDBC {@link java.sql.DatabaseMetaData} 对比列，非数据 SQL（D6）。
 * CUSTOM_SQL 的用户 SQL 仅在受控数据源只读会话运行、经 PolicyEngine 安全解析（D5）。
 */
public enum AssertionType {
    ROW_COUNT,      // expectation: min/max/delta
    NULL_RATE,      // expectation: column + max
    UNIQUENESS,     // expectation: columns[]
    FRESHNESS,      // expectation: ts_column + max_lag_sec
    RANGE,          // expectation: column + min/max
    REFERENTIAL,    // expectation: column + ref_table + ref_column
    CUSTOM_SQL,     // expectation: sql（期望返回 0 行违规） + expect_rows
    SCHEMA          // expectation: expected_columns[] + strict
}
