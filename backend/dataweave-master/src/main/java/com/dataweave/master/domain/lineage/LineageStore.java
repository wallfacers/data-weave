package com.dataweave.master.domain.lineage;

import java.util.List;
import java.util.Map;

/**
 * 血缘写入底座契约。neo4j 实现 = {@code Neo4jLineageStore}（infrastructure）。
 *
 * <p>是 018/019/020 三份 feature 的共享写入契约（设计 §4 图模型 + §6 ColumnEdge 输出契约的实现形）。
 * 018 实现它（neo4j），019 产出 {@link ColumnEdge} 喂入，020 在其写入的图上查询。
 *
 * <h2>不变量</h2>
 * <ul>
 *   <li><b>replace-per-task</b>：每次 {@link #recordTaskIo} 在单个 neo4j 事务内先删该 taskDefId 的旧边
 *       （READS/WRITES/READS_COL/WRITES_COL 及带该 taskDefId 的 FLOWS_TO/DERIVES_FROM），再 MERGE 节点
 *       （按唯一键 upsert）、CREATE 新边；幂等，重复调用边集合一致（SC-003）。</li>
 *   <li><b>数据源去重</b>：{@link DatasourceCoord} 按 (tenantId, ip, port, database) 归一单一 :Datasource（SC-002）。</li>
 *   <li><b>韧性</b>：实现内对 neo4j 异常不外抛业务语义之外的东西；调用方仍以 try-catch 包裹保证主链路不阻断（FR-007）。</li>
 *   <li><b>隔离</b>：所有节点带 tenantId/projectId，写入按其 scope（FR-005）。</li>
 * </ul>
 */
public interface LineageStore {

    /**
     * 记录某任务的设计态血缘（replace-per-task，单事务）。
     *
     * @param tenantId        租户
     * @param projectId       项目
     * @param taskDefId       任务定义 id（边按此私有，replace 锚点）
     * @param versionNo       任务版本号（写入 READS/WRITES 边 version 属性）
     * @param taskName        任务名（写入 :Task 镜像节点）
     * @param ioEdges         表级读写边（设计态）
     * @param columnEdges     列级边（019 产出 + 024 声明兜底）
     * @param declaredSchemas 声明 schema（表名→有序列元数据）；可为空/null（零回归）；先于 columnEdges seed（FR-009）
     */
    void recordTaskIo(long tenantId, long projectId, long taskDefId,
                      Integer versionNo, String taskName,
                      List<IoEdge> ioEdges, List<ColumnEdge> columnEdges,
                      Map<String, ? extends List<? extends DeclaredColumn>> declaredSchemas);

    /** 声明的列元数据（入参委托给 implementation）。 */
    interface DeclaredColumn {
        String name();
        String dataType();
        int ordinal();
    }

    /** 记录/替换某指标的血缘（迁 metric_lineage → :Metric-[:COMPUTED_FROM]->:Table|:Column）。 */
    void recordMetricLineage(MetricEdge edge);

    /**
     * 运行态同步行数/字节（迁 task_run_table_io → :TaskRun-[:SYNCED]->:Table）。
     * 本期定义；运行态采集接入点可随后续埋点。
     */
    void recordSynced(long tenantId, long projectId, String instanceId,
                      TableRef table, Long rowCount, Long bytes, String bizDate);
}
