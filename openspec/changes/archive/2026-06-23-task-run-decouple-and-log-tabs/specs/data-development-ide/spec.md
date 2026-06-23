## MODIFIED Requirements

### Requirement: 任务编辑子 Tab 与按类型语法高亮

编辑子 Tab SHALL 在主区内提供任务的完整编辑能力：名称、类型（SQL/SHELL）、调度配置（优先级/超时/重试）、调度参数（name→表达式）与替换预览。脚本编辑区 MUST 使用 Monaco（`CodeEditor`），并按任务 `type` 切换语法高亮（SQL→`sql`，SHELL→`shell`）。保存 MUST 经任务写接口（草稿）并支持发布。编辑能力来源为并入既有 `TaskEditPanel` 的配置/参数/预览逻辑，但承载形态由侧栏改为主区子 Tab。

编辑子 Tab MUST 维护脏态（dirty）：加载任务时读取 `hasDraftChange`；任一可编辑字段（脚本/类型/配置/参数）发生变更后，「保存草稿」按钮 MUST 呈现脏态视觉（「● 待保存」），保存成功后清除脏态。发布按钮的可用性 MUST 由「存在已保存但未发布的改动」（`hasDraftChange`）决定 —— 无未发布改动时禁用；其视觉风格 MUST 与「保存草稿」按钮一致（同 `variant`），不以更强调的样式区分。

#### Scenario: SQL 任务高亮

- **WHEN** 用户打开一个 `type=SQL` 的任务编辑子 Tab
- **THEN** 脚本区以 SQL 语法高亮渲染内容

#### Scenario: SHELL 任务高亮

- **WHEN** 用户打开一个 `type=SHELL` 的任务编辑子 Tab
- **THEN** 脚本区以 shell 语法高亮渲染内容

#### Scenario: 改动后呈现待保存脏态

- **WHEN** 用户在编辑子 Tab 修改脚本或任一配置字段而尚未保存
- **THEN** 「保存草稿」按钮呈现「● 待保存」脏态视觉

#### Scenario: 编辑后保存草稿清脏态

- **WHEN** 用户在编辑子 Tab 修改脚本/配置后点击保存
- **THEN** 系统经任务写接口保存草稿，成功后清除脏标记，「保存草稿」回到非脏态

#### Scenario: 发布按钮按未发布改动启用且风格对齐

- **WHEN** 任务存在已保存但未发布的改动（hasDraftChange 为真）
- **THEN** 发布按钮可点击，且其视觉风格与「保存草稿」按钮一致；当无未发布改动时发布按钮禁用

### Requirement: 跑后即观测（日志与 DAG 运行态）

数据开发 IDE SHALL 在"跑"之后提供就地观测。编辑子 Tab MUST 能内嵌运行实例的日志流（复用 `/api/ops/instances/{id}/logs/stream`），且日志承载形态 MUST 为 **Tabs 容器**：每次运行新开一个日志 tab，tab 命名 MUST 为「任务名 + 运行时间」；多次运行的日志 tab 并存可切换，不互相覆盖。日志 tab 内为实时滚屏（逐行追加自动滚底，体验同 AI token 流），视觉风格对齐 DataWorks 运行日志。Tabs 容器 MUST 预留「结果集 tab」契约位，供后续 SQL 结果集展示落入（本期不实现结果集渲染）。画布子 Tab MUST 能订阅所属工作流实例的事件流（`/api/ops/workflow-instances/{id}/events/stream`）并把节点运行态**实时叠加变色**到对应 DAG 节点。

#### Scenario: 跑任务后看日志 tab

- **WHEN** 用户在编辑子 Tab 点击「运行」并拿到 `instanceId`
- **THEN** 下方 Tabs 容器新开一个命名为「任务名 + 运行时间」的日志 tab 并激活，开始流式追加该实例日志

#### Scenario: 多次运行日志并存

- **WHEN** 用户对同一任务先后运行两次
- **THEN** Tabs 容器中并存两个日志 tab（各带各自运行时间），可自由切换查看，互不覆盖

#### Scenario: 结果集 tab 位预留

- **WHEN** 用户运行一个 SQL 任务（本期）
- **THEN** Tabs 容器结构支持承载结果集 tab，但本期仅呈现日志 tab，不渲染结果集
