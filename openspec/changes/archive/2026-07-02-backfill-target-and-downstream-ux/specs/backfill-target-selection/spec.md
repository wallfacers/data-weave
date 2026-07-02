## ADDED Requirements

### Requirement: 可搜索的补数据目标选择器

补数据弹窗 SHALL 以按名称搜索的选择器定位目标,替代手输数字 ID。用户先选 `targetType`(task/workflow),在搜索框输入关键词,系统经既有 `GET /api/tasks?keyword=` 或 `GET /api/workflows?keyword=` 返回候选,每条候选展示 名称 · catalog 路径 · 负责人。选中后内部持有该对象 id,数字 ID 不出现在用户可见交互中。

#### Scenario: 按名称搜索任务并选中
- **WHEN** 用户在 task 类型下输入关键词「订单」
- **THEN** 选择器展示名称匹配「订单」的任务候选(含 名称/路径/负责人),选中其一后弹窗持有该任务 id,目标区显示其名称而非数字 ID

#### Scenario: 切换目标类型搜索工作流
- **WHEN** 用户将 `targetType` 切到 workflow 并输入关键词
- **THEN** 选择器改查 `GET /api/workflows?keyword=`,展示匹配的工作流候选

#### Scenario: 未选中目标不可提交
- **WHEN** 用户未从选择器选中任何对象即尝试提交
- **THEN** 提交被拦截并提示需先选择目标(等价于此前 targetId 为空的校验)

### Requirement: 从对象就地发起补数据

系统 SHALL 在任务详情、血缘图节点、catalog 树任务节点提供「补数据」就地入口,点击后打开补数据弹窗并预填目标对象(`targetType` + 对应 id),跳过搜索步骤。就地入口仍打开同一弹窗(用户可继续调整日期与下游范围、经同一闸门),不做一键直发。

#### Scenario: 任务详情就地补数据
- **WHEN** 用户在某任务详情页点击「补数据」
- **THEN** 打开补数据弹窗,目标已预填为该任务(task + 其 id),无需搜索

#### Scenario: 血缘图节点就地补数据
- **WHEN** 用户在血缘图上对某表节点触发「补数据」
- **THEN** 打开弹窗并预填为该表的产出任务(task + taskDefId)

#### Scenario: catalog 树节点就地补数据
- **WHEN** 用户在 catalog 树的任务叶子节点触发「补数据」
- **THEN** 打开弹窗并预填为该任务

### Requirement: 移除无效的下游开关

补数据弹窗 SHALL 移除此前的裸 `包含下游` 复选框(其后端语义为空、不产生行为差异),改由「下游影响范围」交互(见 `data-backfill`)承载下游选择。

#### Scenario: 旧 checkbox 不再呈现
- **WHEN** 用户打开补数据弹窗
- **THEN** 不出现无行为差异的「包含下游」复选框
