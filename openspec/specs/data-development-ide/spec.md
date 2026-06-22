# data-development-ide Specification

## Purpose
TBD - created by archiving change data-development-ide. Update Purpose after archive.
## Requirements
### Requirement: 数据开发 IDE 壳与内层子 Tab

系统 SHALL 提供一个"数据开发"工作区视图（由原 `workflow-canvas` 视图升格，标题为"数据开发"），布局为**左侧常驻类目树 + 右侧内层子 Tab 区**。内层子 Tab 区 MUST 复用与顶层一致的 `TabStrip` 组件风格，自管一份独立的子 Tab 列表（不与顶层 Workspace tab 系统共享）。子 Tab 分两类：**画布子 Tab**（一个工作流一个）与**编辑子 Tab**（一个任务一个）。左侧类目树在所有子 Tab 间 MUST 保持常驻、不随子 Tab 切换而卸载。

#### Scenario: 打开数据开发视图呈现壳

- **WHEN** 用户从启动菜单或深链打开"数据开发"视图
- **THEN** 主区呈现左侧类目树面板与右侧内层子 Tab 区（初始无子 Tab 时右侧显示空态引导），类目树常驻

#### Scenario: 子 Tab 风格与顶层一致

- **WHEN** 内层子 Tab 区存在一个或多个子 Tab
- **THEN** 子 Tab 条以与顶层 Workspace 相同的 `TabStrip` 视觉呈现（图标 + 标题 + 可关闭），且其开关不影响顶层 tab

#### Scenario: 类目树跨子 Tab 常驻

- **WHEN** 用户在画布子 Tab 与编辑子 Tab 之间切换
- **THEN** 左侧类目树保持挂载与展开态不变，不重新加载

### Requirement: 点树开子 Tab 与去重激活

点击类目树中的工作流叶子 SHALL 打开（或激活已存在的）对应**画布子 Tab**；点击任务叶子 SHALL 打开（或激活已存在的）对应**编辑子 Tab**。同一资产 MUST 至多对应一个子 Tab（按 `{kind,id}` 去重），重复点击只激活不新建。

#### Scenario: 点工作流开画布子 Tab

- **WHEN** 用户点击类目树中一个工作流叶子
- **THEN** 内层区打开一个绑定该工作流的画布子 Tab 并激活；若已存在则仅激活

#### Scenario: 点任务开编辑子 Tab

- **WHEN** 用户点击类目树中一个任务叶子
- **THEN** 内层区打开一个绑定该任务的编辑子 Tab 并激活；若已存在则仅激活

#### Scenario: 同一任务不重复开 Tab

- **WHEN** 某任务的编辑子 Tab 已打开，用户再次点击该任务叶子
- **THEN** 系统激活既有子 Tab，不新建第二个

### Requirement: 任务编辑子 Tab 与按类型语法高亮

编辑子 Tab SHALL 在主区内提供任务的完整编辑能力：名称、类型（SQL/SHELL）、调度配置（优先级/超时/重试）、调度参数（name→表达式）与替换预览。脚本编辑区 MUST 使用 Monaco（`CodeEditor`），并按任务 `type` 切换语法高亮（SQL→`sql`，SHELL→`shell`）。保存 MUST 经任务写接口（草稿）并支持发布。编辑能力来源为并入既有 `TaskEditPanel` 的配置/参数/预览逻辑，但承载形态由侧栏改为主区子 Tab。

#### Scenario: SQL 任务高亮

- **WHEN** 用户打开一个 `type=SQL` 的任务编辑子 Tab
- **THEN** 脚本区以 SQL 语法高亮渲染内容

#### Scenario: SHELL 任务高亮

- **WHEN** 用户打开一个 `type=SHELL` 的任务编辑子 Tab
- **THEN** 脚本区以 shell 语法高亮渲染内容

#### Scenario: 编辑后保存草稿

- **WHEN** 用户在编辑子 Tab 修改脚本/配置后点击保存
- **THEN** 系统经任务写接口保存草稿，成功后清除脏标记

### Requirement: 新建即进编辑态

在数据开发 IDE 内新建任务 SHALL 经 Dialog（非原生弹框）收集名称与类型，创建成功后 MUST 直接打开该任务的编辑子 Tab 进入编辑态，而非跳转侧栏或留在列表。

#### Scenario: 新建任务后进编辑子 Tab

- **WHEN** 用户在 IDE 内通过 Dialog 提交新任务的名称与类型
- **THEN** 系统创建任务草稿并立即打开其编辑子 Tab 激活，焦点落在编辑区

### Requirement: 跑后即观测（日志与 DAG 运行态）

数据开发 IDE SHALL 在"跑"之后提供就地观测。编辑子 Tab MUST 能内嵌该任务最近一次手动运行实例的日志流（复用 `/api/ops/instances/{id}/logs/stream`）。画布子 Tab MUST 能订阅所属工作流实例的事件流（`/api/ops/workflow-instances/{id}/events/stream`）并把节点运行态**实时叠加变色**到对应 DAG 节点。

#### Scenario: 跑任务后看日志

- **WHEN** 用户在编辑子 Tab 点击"运行"并拿到 `instanceId`
- **THEN** 编辑子 Tab 内嵌区域开始流式追加该实例日志

#### Scenario: 跑工作流后节点变色

- **WHEN** 用户在画布子 Tab 点击"运行"，工作流实例开始执行
- **THEN** 画布订阅该实例事件流，节点按运行态（运行中/成功/失败/等待）实时变色

### Requirement: 运行入口的发布前置

"运行"为触发正式实例，MUST 以"已发布/已上线"为前置。任务未发布时编辑子 Tab 的"运行"按钮 MUST 禁用并显示"需先发布"且提供发布入口；工作流无已发布版本时画布子 Tab 的"运行"按钮同理。系统 MUST NOT 把发布副作用（出版本快照、转上线）静默合并进"运行"动作；如未来提供"发布并运行"，其确认 MUST 明示"正在发布上线"。

#### Scenario: 未发布任务运行禁用

- **WHEN** 用户打开一个仅有草稿、从未发布任务的编辑子 Tab
- **THEN** "运行"按钮禁用，提示"需先发布"，并提供跳转发布的入口

#### Scenario: 未上线工作流运行禁用

- **WHEN** 用户打开一个无已发布版本工作流的画布子 Tab
- **THEN** "运行"按钮禁用，提示需先发布上线

### Requirement: 消除原生弹框

数据开发 IDE 及其类目树涉及的交互式输入/确认 MUST NOT 使用浏览器原生 `window.prompt/confirm/alert`。系统 SHALL 提供 base 风格的 `Dialog` 组件承载这些表单/确认，仅在项目内确无法用页内交互完成的极端场景才使用模态框。

#### Scenario: 建文件夹用 Dialog

- **WHEN** 用户在类目树新建文件夹
- **THEN** 系统弹出 `Dialog` 收集文件夹名，而非原生 `window.prompt`

#### Scenario: 删除资产用 Dialog 确认

- **WHEN** 用户删除一个任务或工作流
- **THEN** 系统以 `Dialog` 二次确认，而非原生 `window.confirm`

### Requirement: 运行 Tab 状态圆点反映实例终态

编辑子 Tab 内日志 Tabs 容器的每个运行 tab 左侧 SHALL 显示一个状态圆点，反映对应实例的**执行状态**（非 SSE 连接状态）：实例 RUNNING 时圆点为绿色脉冲，SUCCESS 时为绿色稳态（远程完成），FAILED 时为红色（运行错误），STOPPED 时为灰色（已终止）。圆点状态 SHALL 由日志流的连接活性与终态 outcome 合成——SSE 仍连接且未收到终态 outcome 时为「运行中」（绿色脉冲）；收到 `end` 事件携带的终态 outcome 后以终态颜色覆盖（绿色稳态/红色/灰色），且不再回退为「运行中」。圆点配色 MUST 使用 DESIGN.md 语义 token（`bg-success`/`bg-destructive`/`bg-muted-foreground`/`bg-warning`），不得使用硬编码调色板色值。圆点 SHALL 提供 tooltip（复用既有 i18n state 文案）表明其当前含义。

#### Scenario: 运行中绿色脉冲

- **WHEN** 某运行 tab 对应实例处于 RUNNING 且其日志 SSE 仍连接
- **THEN** 该 tab 左侧圆点呈现绿色脉冲

#### Scenario: 成功绿色稳态

- **WHEN** 某运行 tab 对应实例终态为 SUCCESS
- **THEN** 该 tab 左侧圆点呈现绿色稳态（不脉冲）

#### Scenario: 失败红色

- **WHEN** 某运行 tab 对应实例终态为 FAILED
- **THEN** 该 tab 左侧圆点呈现红色

#### Scenario: 已终止灰色

- **WHEN** 某运行 tab 对应实例终态为 STOPPED
- **THEN** 该 tab 左侧圆点呈现灰色

#### Scenario: 终态覆盖连接态不回退

- **WHEN** 某运行 tab 的日志流已收到 SUCCESS 终态 outcome，但 SSE 连接尚未物理关闭
- **THEN** 圆点为绿色稳态（终态覆盖连接态），不回退为「运行中」脉冲

#### Scenario: 多运行 tab 状态各自独立

- **WHEN** 日志 Tabs 容器并存两个运行 tab，其一终态 SUCCESS、另一终态 FAILED
- **THEN** 两个 tab 的圆点分别为绿色稳态与红色，各自独立、互不影响

