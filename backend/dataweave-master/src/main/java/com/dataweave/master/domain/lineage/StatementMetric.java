package com.dataweave.master.domain.lineage;

/**
 * 单条 SQL 语句的执行指标（运行态采集，feature 025-lineage-synced-rows）。
 *
 * <p>由 {@code SqlTaskExecutor} 在 per-statement 执行后收集（仅 {@code updateCount>=0}，
 * SELECT/DDL 不收），经 {@code ExecutionResult.statementMetrics} / 上报 DTO 透传到 master，
 * 由 {@code WorkerReportService.reportFinished} 用 {@code SqlTableExtractor} 解析写表 →
 * {@code LineageStore.recordSynced}。
 *
 * <p><b>放置位置（master.domain.lineage）</b>：模块依赖方向是 {@code worker → master}
 *（worker 依赖 master，先例 {@code DriverJar}；master 不依赖 worker）。该类型需跨 worker / api /
 * master 三模块共享（worker 产、api 透传、master 消费），故只能放 master 让三模块都见——
 * {@code worker.ExecutionResult} 引用本类型与引用 {@code DriverJar} 同模式。
 *
 * @param sqlText     语句原文（写表解析输入，喂 {@code SqlTableExtractor.extract}）
 * @param updateCount JDBC affected-rows（>=0；INSERT/MERGE/UPDATE/DELETE 影响行数）
 */
public record StatementMetric(String sqlText, long updateCount) {}
