# Contract: AgentLineageExtractor（第 4 条抽取通道）+ 异步富化

## C1. 抽取器契约（复用既有 `ScriptLineageExtractor`）

```java
public interface ScriptLineageExtractor {
    boolean supports(String taskType);      // 运行期可用性开关
    ScriptExtraction extract(ScriptSource source);
}
```

`AgentLineageExtractor implements ScriptLineageExtractor`：

- `supports(taskType)` MUST 返回 true 仅当：① 该租户/项目有 `enabled=1` 配置；② taskType ∈ {PYTHON, SHELL, SPARK}（脚本），或 SQL 任务被标记 Calcite 解析失败（D7）。未配置/禁用 → false，整体旁路（FR-019）。
- `extract` MUST 绝不外抛：超时/鉴权失败/非法结构 → 返回空 `ScriptExtraction` + hint 留痕（FR-006）。
- 产出边 `channel = Source.SCRIPT_AGENT`。

**关键约束**：`AgentLineageExtractor` 因异步，**不**注册进 `ScriptLineageService` 的同步 `extractors` 列表（否则会拖慢 push）。它由 `LineageAgentEnricher` 在异步流程内直接调用。

## C2. 防幻觉校验（FR-005 / FR-016）

产出边被接受的充要条件：
1. 结构合法（JSON 可解析为 `AgentExtraction`）。
2. 每个表名能在脚本/SQL 文本中**字面定位**（大小写不敏感、限定名或裸名任一命中）。
3. 若提供了真实 schema 上下文（US3），字段边的列名 MUST 落在该表真实列集合内；越界列拒收。

违反任一 → 该边丢弃 + `lineage_agent_call.status=REJECTED` + hint。

## C3. 异步富化编排（LineageAgentEnricher）

```
push 同步路径（TaskService.recordLineage / ProjectSyncService）
  └─ 记录确定性血缘（Calcite + 内嵌SQL + 规则 + 小模型）不变
  └─ eventBus.publish(LineageAgentEnrichmentRequested{tenant,project,taskDefId,taskType,calciteParsed})

LineageAgentEnricher.onEvent(...)  [异步、有界线程池、令牌桶限频]
  1. 加载配置；enabled=0 或不 supports → 旁路返回
  2. 重载任务 content + 构造 DatasourceBoundCatalog（连库取真实 schema 作接地）
  3. AgentLineageExtractor.extract(...) → AgentExtraction（含防幻觉校验）
  4. 重算确定性全集（复现同步结果） + 合并 AI 边，按 CHANNEL_PRIORITY 消解
  5. lineageStore.recordTaskIo(...) 一次全量 keyed replace（superset 幂等覆盖，不擦除确定性边）
  6. 写 lineage_agent_call 审计（SUCCESS/DEGRADED/REJECTED, latency, edges）
```

**不变量**：
- 步骤 3–6 任一失败 MUST NOT 回退步骤已入图谱的确定性血缘（FR-004b）。
- 外呼总耗时 MUST ≤ 配置 `timeout_ms`（FR-022）。
- 未开启项目 MUST 零外呼（步骤 1 提前返回，FR-019 / SC-005）。

## C4. 冲突消解优先级（FR-004a）

同 (direction, tableKey) 键胜者 = `CHANNEL_PRIORITY` 最高者：
`SCRIPT_SQL > SCRIPT_INFERRED > SCRIPT_AGENT > SCRIPT_MODEL`。被覆盖来源可留痕。人工 `REMOVED` 键抑制重放，`CONFIRMED` 升级置信度（复用 `ScriptLineageCorrectionGate`）。
