## ADDED Requirements

### Requirement: 指标血缘记录

系统 SHALL 记录指标的下游血缘边（指标 → SQL/物理表/报表），存于 `metric_lineage`。MVP 阶段至少支持「指标 → 来源物理表」的血缘。

#### Scenario: 注册指标时生成来源表血缘
- **WHEN** 注册 GMV 指标（来源表 `orders`）
- **THEN** `metric_lineage` 新增一条 `metric → orders` 的血缘边

### Requirement: 血缘问答

Agent SHALL 回答指标的影响链路问题，返回「指标 → SQL → 物理表」结构化关系。

#### Scenario: 查询 GMV 的影响表
- **WHEN** 用户提问「GMV 受哪些表影响」
- **THEN** Agent 从 `metric_lineage` 查出 GMV 关联的物理表（含 `orders`）
- **AND** 以结构化形式（链路/表格）返回，而非仅纯文本
