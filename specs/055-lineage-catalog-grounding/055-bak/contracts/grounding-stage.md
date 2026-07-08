# Contract: Grounding 阶段 + 处置审计

## C1. `CatalogGroundingService`（新增）

```java
/** 对一批候选边执行目录接地：三态裁决 + 来源分类 + 系统排除 + 处置留痕。永不抛。 */
final class GroundingResult {
    List<IoEdge> ioEdges;            // 过滤/升级后的表级边
    List<ColumnEdge> columnEdges;    // 连带剔除后的列级边
    List<GroundingDisposition> dispositions;  // 待落审计表
}

GroundingResult ground(
    long tenantId, long projectId, Long taskDefId,
    List<IoEdge> mergedIo, List<ColumnEdge> mergedCol,
    DatasourceBoundCatalog readCatalog,   // dsId 闭包
    DatasourceBoundCatalog writeCatalog,  // targetDsId ?? dsId 闭包
    SystemNamespaceClassifier classifier,
    String readEngine, String writeEngine);
```

**逐边算法**（表级）：
1. 路由目录：`READS` 用 readCatalog（readEngine）、`WRITES` 用 writeCatalog（writeEngine）。
2. 规范化候选名（复用 `parseQualifiedName`）；失败 → verdict=UNKNOWN, disposition=RETAINED。规范化后先 `catalog.evict(规范化名)` 再 probe（FR-013 重 push 失效，刷新 PRESENT 侧陈旧缓存）。
3. 系统命名空间判定（`classifier.isSystem(engine, qualifiedName)`）：命中 → verdict=SYSTEM_EXCLUDED；推断类 source → EXCLUDED（剔除），确定性 source → RETAINED（留痕不剔）。
4. 否则 `catalog.probeExistence(...)`：
   - `PRESENT` → ADOPTED：保留，若 confidence==UNVERIFIED 则升 CONFIRMED。
   - `UNKNOWN` → RETAINED：原样保留。
   - `ABSENT` → 推断类 source → DROPPED（剔除）；确定性 source → RETAINED（留痕不剔，FR-011）。
5. 被 DROPPED/EXCLUDED 的表：连带剔除 `mergedCol` 中 `srcTable`/`dstTable` 规范化名命中该表的列级边（记为该表处置的一部分）。

**来源分类**：
```
推断类(可剔) = { SCRIPT_INFERRED, SCRIPT_MODEL, SCRIPT_AGENT }
确定性(不可剔) = 其余 Source ∪ { null }   // SQL_PARSED, SCRIPT_SQL, AGENT, FORM, null
```

**安全**：任一候选处理抛异常 → 该候选降级 RETAINED（不剔）；整体不抛。

## C2. `GroundingDispositionRepository`（新增，镜像 `AgentConfigRepository.insertCall`）

```java
void insert(long tenantId, long projectId, Long taskDefId,
            String candidate, String direction, String sourceChannel,
            Long datasourceId, String verdict, String disposition, String reason);
```
- JdbcTemplate 插 `lineage_grounding_disposition`；DROPPED/EXCLUDED/ADOPTED 必插，RETAINED(UNKNOWN) 按实现采样。

## C3. `LineageAgentEnricher` 集成点（改）

在 `enrich()` 内：
- 早退门控解耦：`groundingEnabled(kill-switch) && dsBound` 时，即使 AI 未启用也重算确定性全集 + grounding + replace；AI 边生成仍受 `cfg.enabled && shouldEnrich` 门控。
- 位置：`dedupeIo/dedupeCol` 之后、`recordTaskIo` 之前调 `CatalogGroundingService.ground(...)`，用其返回的 `ioEdges/columnEdges` 做 keyed replace，再逐条 `dispositionRepository.insert(...)`。
- 冲突消解（FR-012）：`dedupeIo/dedupeCol` 的 source-priority 之上，同键同 source 时 `CONFIRMED` 胜 `UNVERIFIED`。
- 审计：grounding 处置落 `lineage_grounding_disposition`；AI 外呼审计仍走既有 `lineage_agent_call`（两表各司其职）。

## 不变量

- push 同步返回时延不受影响（grounding 全在异步 enrich 线程，FR-008/SC-005）。
- 未绑定数据源 → 所有候选 UNKNOWN → RETAINED → 边集与今天完全一致（SC-003）。
- grounding 任何故障 → 全候选 RETAINED，recordTaskIo 仍写既有边集，push/上线不受影响（SC-004）。
- 确定性来源边永不被 grounding 剔除（FR-011）。
