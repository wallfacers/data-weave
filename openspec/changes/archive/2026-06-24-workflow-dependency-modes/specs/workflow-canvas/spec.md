## MODIFIED Requirements

### Requirement: 画布边操作与删除键

画布 SHALL 支持删除边：边可被选中并通过右键菜单「删除」移除，也 MUST 支持选中后按删除键删除。React Flow 的 `deleteKeyCode` MUST 同时识别 `Backspace` 与 `Delete`，对选中的节点与边均生效。边右键菜单 MUST 额外提供「设为强依赖」/「设为弱依赖」切换项，反映并编辑该边的 `strength`（`STRONG`/`WEAK`），当前强度 MUST 在菜单上可视标识。任何删除或强度变更 MUST 标记未保存脏状态，不静默丢弃。

#### Scenario: 选中边按 Delete 删除

- **WHEN** 用户点击选中一条边后按 `Delete` 键
- **THEN** 该边从画布移除并置脏

#### Scenario: Backspace 仍可删除

- **WHEN** 用户选中一个节点或一条边后按 `Backspace` 键
- **THEN** 选中元素被删除并置脏（保留既有默认键位）

#### Scenario: 右击边删除

- **WHEN** 用户右击一条边并点「删除」
- **THEN** 该边从画布移除并置脏

#### Scenario: 右击边切换强弱依赖

- **WHEN** 用户右击一条边并点「设为弱依赖」
- **THEN** 该边标记为 `WEAK`（视觉可区分），画布置脏，保存后随整图 PUT 携带 `strength`

### Requirement: 画布节点右键菜单

画布 SHALL 为节点提供右键上下文菜单（启用既有 `<ContextMenu>` 组件），在运行态与编辑态下均可用。TASK 节点菜单 MUST 含：①「查看日志」——打开/激活该节点日志 Tab（运行中或已完成均可）；②「单独运行」（`ONLY_NODE`）——脱离当前工作流，按节点绑定的 `task_id` 调用现成 `POST /api/tasks/{taskId}/run`，不进当前工作流实例、不触发下游；③「运行到本节点」（`TO_NODE`）——调用 `POST /api/workflows/{id}/run` 携带 `scope=TO_NODE` 与 `targetNodeKey`，含上游闭包；④「运行下游」（`DOWNSTREAM`）——同接口 `scope=DOWNSTREAM`，含后继闭包；⑤「删除节点」。`TO_NODE`/`DOWNSTREAM` 成功后 MUST 用返回的 `workflowInstanceId` 接管事件流叠加运行态、顶运行节点日志。VIRTUAL 节点无任务，「查看日志」与所有运行项 MUST 置灰或不出现，仅保留「删除节点」。工作流未上线时，所有运行项 MUST 禁用并提示需先发布。

#### Scenario: 右击 TASK 节点查看日志

- **WHEN** 用户右击一个有运行实例的 TASK 节点并点「查看日志」
- **THEN** 画布打开/激活该节点对应实例的日志 Tab

#### Scenario: 单独运行节点开新日志 Tab

- **WHEN** 用户右击一个 TASK 节点并点「单独运行」
- **THEN** 前端以该节点 `task_id` 调用 `POST /api/tasks/{taskId}/run`，运行被闸门放行后用 `resultInstanceId` 打开一个日志 Tab；该运行不进当前工作流实例、不触发下游节点

#### Scenario: 运行到本节点接管子图实例

- **WHEN** 用户右击一个 TASK 节点点「运行到本节点」
- **THEN** 前端调用 `POST /api/workflows/{id}/run`（`scope=TO_NODE`、`targetNodeKey`=该节点），接管返回 `workflowInstanceId` 的事件流给子图节点变色，并顶运行节点日志

#### Scenario: VIRTUAL 节点运行项不可用

- **WHEN** 用户右击一个 VIRTUAL 节点
- **THEN** 菜单中「单独运行」「运行到本节点」「运行下游」「查看日志」置灰或不出现，「删除节点」可用

#### Scenario: 右击删除节点置脏

- **WHEN** 用户右击节点并点「删除节点」
- **THEN** 画布移除该节点（及其关联边），标记未保存脏状态

#### Scenario: 未上线时运行范围禁用

- **WHEN** 工作流未上线，用户右击节点
- **THEN** 「单独运行」「运行到本节点」「运行下游」置灰，提示需先发布

## ADDED Requirements

### Requirement: 运行范围选择与跨周期依赖配置入口

画布运行入口（工具栏「运行」按钮及节点右键菜单）SHALL 支持选择运行范围。从工具栏发起运行时 SHALL 弹出运行 Dialog，供选择 `scope`（`FULL`/`TO_NODE`/`DOWNSTREAM`/`ONLY_NODE`）及目标节点（非 `FULL` 时，`ONLY_NODE` 转发到单任务接口）。配置面板 SHALL 提供跨周期依赖的可视化配置：列出本工作流节点的跨周期依赖、新增（选依赖节点 + `date_offset` + `earliest_biz_date` 日期选择器）、删除，编辑即生效。所有新增 UI copy MUST 走 next-intl 双语 key（`zh-CN`/`en-US` 两 bundle 等集），MUST NOT 出现硬编码中文回退。

#### Scenario: 工具栏运行弹出范围选择

- **WHEN** 用户点击工具栏「运行」
- **THEN** 弹出 Dialog 供选 `scope` 与目标节点，确认后按所选范围触发

#### Scenario: 配置面板配自依赖

- **WHEN** 用户在配置面板为本节点新增自依赖，选 LAST_DAY 并填最早回溯日期
- **THEN** 面板调用 `POST /api/workflows/{id}/dependencies` 保存，列表即时刷新

#### Scenario: 运行范围与依赖配置 UI 双语

- **WHEN** 用户切换 UI 语言（zh-CN/en-US）
- **THEN** 运行范围选项、依赖配置表单文案均按当前 locale 本地化，console 无 missing-key 报错
