# Contract: `LineageStore` 写入接口 + `ColumnEdge` 形参

**Feature**: 018-lineage-neo4j-store | **Date**: 2026-06-30

这是 018/019/020 三份 feature 的**共享写入契约**（设计 §4 图模型 + §6 `ColumnEdge` 输出契约的实现形）。018 实现它（neo4j），019 产出 `ColumnEdge` 喂入，020 在其写入的图上查询。**先定契约、三份并行**——本文件即 018 暴露给 019/020 的稳定接口签名。

位置：`backend/dataweave-master/src/main/java/com/dataweave/master/domain/lineage/`（纯领域契约，无框架依赖；neo4j 实现 `Neo4jLineageStore` 在 infrastructure 层）。

---

## 1. 领域记录（输入契约）

```java
package com.dataweave.master.domain.lineage;

import java.util.List;

/** 数据源去重身份（FR-004）。物理坐标去重；凭据不进键；缺坐标走降级身份。 */
public record DatasourceCoord(
        long tenantId,
        long projectId,
        String ip,          // host，小写 trim；缺则 null（走 fallbackName）
        Integer port,       // 缺省补 datasource_type.default_port；缺则 null
        String database,    // 小写 trim；缺则 null
        String fallbackName // 缺连接坐标时的确定性降级名（如 datasource 配置 name 或 id）
) {
    /** 规范化合成唯一键 dsKey：有坐标→ tenantId|ip|port|database；否则 → tenantId|datasource:<fallbackName>。 */
    public String dsKey() { /* 实现：规范化拼装 */ return null; }
}

/** 表引用：归属某 Datasource（去重后图 id 由 store 解析）+ 限定名 + 分层。 */
public record TableRef(
        DatasourceCoord datasource,
        String qualifiedName,   // 大小写规范化与现 SqlTableExtractor 一致
        String layer            // ODS/DWD/DWS/ADS，可 null（由命名前缀推导）
) {}

/** 一条任务读写边（设计态，表级）。direction = READS | WRITES。 */
public record IoEdge(
        TableRef table,
        Direction direction,
        Source source,          // AGENT / SQL_PARSED / FORM
        Confidence confidence   // CONFIRMED / UNVERIFIED / CONFLICT
) {}

public enum Direction { READS, WRITES }
public enum Source { AGENT, SQL_PARSED, FORM }
public enum Confidence { CONFIRMED, UNVERIFIED, CONFLICT }
public enum Transform { DIRECT, EXPRESSION, AGGREGATE }

/**
 * 列级血缘边（设计 §6 输出契约）。由 019 SqlColumnLineageExtractor 产出，018 本期定义并能写入。
 * 本期写入路径接受 List<ColumnEdge>（可空）；列映射的产生不在 018。
 */
public record ColumnEdge(
        TableRef srcTable, String srcCol,
        TableRef dstTable, String dstCol,
        Transform transform,
        Confidence confidence
) {}

/** 指标血缘边（迁现 metric_lineage）。downstream 指向表或列。 */
public record MetricEdge(
        long tenantId, long projectId,
        String metricType,      // ATOMIC / DERIVED
        long metricId,
        String metricName,
        String downstreamType,  // TABLE / COLUMN
        String downstreamRef    // 表限定名 / 列引用
) {}
```

---

## 2. 写入接口

```java
package com.dataweave.master.domain.lineage;

import java.util.List;

/**
 * 血缘写入底座契约。neo4j 实现 = Neo4jLineageStore（infrastructure）。
 *
 * 不变量：
 *  - replace-per-task：每次 recordTaskIo 在单个 neo4j 事务内先删该 taskDefId 的旧边，
 *    再 MERGE 节点（按唯一键 upsert）、CREATE 新边；幂等，重复调用边集合一致（SC-003）。
 *  - 数据源去重：DatasourceCoord 按 (tenantId, ip, port, database) 归一单一 :Datasource（SC-002）。
 *  - 韧性：实现内对 neo4j 异常不外抛业务语义之外的东西；调用方仍以 try-catch 包裹保证主链路不阻断（FR-007）。
 *  - 隔离：所有节点带 tenantId/projectId，写入按其 scope（FR-005）。
 */
public interface LineageStore {

    /**
     * 记录某任务的设计态血缘（replace-per-task，单事务）。
     *
     * @param tenantId   租户
     * @param projectId  项目
     * @param taskDefId  任务定义 id（边按此私有，replace 锚点）
     * @param versionNo  任务版本号（写入 READS/WRITES 边 version 属性）
     * @param taskName   任务名（写入 :Task 镜像节点）
     * @param ioEdges    表级读写边（设计态）
     * @param columnEdges 列级边（019 产出；本期可传 List.of()，能写 :Column/DERIVES_FROM）
     */
    void recordTaskIo(long tenantId, long projectId, long taskDefId,
                      Integer versionNo, String taskName,
                      List<IoEdge> ioEdges, List<ColumnEdge> columnEdges);

    /** 记录/替换某指标的血缘（迁 metric_lineage → :Metric-[:COMPUTED_FROM]->:Table|:Column）。 */
    void recordMetricLineage(MetricEdge edge);

    /**
     * 运行态同步行数/字节（迁 task_run_table_io → :TaskRun-[:SYNCED]->:Table）。
     * 本期定义；运行态采集接入点可随后续埋点。
     */
    void recordSynced(long tenantId, long projectId, String instanceId,
                      TableRef table, Long rowCount, Long bytes, String bizDate);
}
```

---

## 3. 启动期 schema（约束/索引）

由 `Neo4jSchemaInitializer`（infrastructure，`@PostConstruct`/`ApplicationRunner`）幂等执行 data-model.md §3 的 `CREATE CONSTRAINT ... IF NOT EXISTS` / `CREATE INDEX ... IF NOT EXISTS`。约束建立在合成唯一键属性（`dsKey`/`tableKey`/`columnKey`/`metricKey`/`taskKey`/`instanceId`）上。

---

## 4. 三触发点接入（写入链路，FR-002）

| 触发点 | 位置 | 改造 |
|--------|------|------|
| `createAndOnline` | `TaskService.recordLineage` | 现 `lineageGraphService.recordDesignTimeIo` → `lineageStore.recordTaskIo`（保留 A×B 交叉校验 buildEdges 逻辑，产出 `IoEdge` 而非 PG `EdgeInput`） |
| `publish`（DRAFT→ONLINE）| 沿用设计态写入 | 复用同一 recordTaskIo（不变语义） |
| **`push`（CLI 同步）** | `ProjectSyncService.push` | **本期补上**：每个新增/修改任务在 push 落库后触发 recordTaskIo（与 createAndOnline 语义一致，FR-002 缺口补齐） |

调用方（TaskService / ProjectSyncService）以 try-catch 包裹 recordTaskIo，失败记日志不阻断主链路（FR-007）。

---

## 5. 契约稳定性承诺（给 019/020）

- **019** 依赖 `ColumnEdge` / `TableRef` / `Transform` / `Confidence` 形参 —— 签名锁定，019 只负责**产生** `List<ColumnEdge>` 并通过 `recordTaskIo` 的 `columnEdges` 入参喂入。
- **020** 依赖图模型（data-model.md §1/§2 节点/关系/约束）—— 在 018 写入的图上做 Cypher 查询（变长路径 `[:FLOWS_TO|DERIVES_FROM*]`）。020 不改写入契约。
- 018 先出接口桩（throw 或空实现）即可让 019/020 编译并行；018 落实现后语义生效。
