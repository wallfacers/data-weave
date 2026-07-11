# Feature Specification: 监督席

**Feature Branch**: `064-supervisor-desk`

**Created**: 2026-07-11

**Status**: Draft

**Input**: User description: "监督席——故障工单队列，异常自动开单、自动愈合。重构页面前端组件使其符合设计规范：tab 下划线式、内容展示事件时间线、抽屉风格与查看 DAG/日志详情一致、修复滚动条与边框重合。"

## Clarifications

### Session 2026-07-11

- Q: 监督席重构后 EventCenterView/AlertsView 如何处理？ → A: 就地替换 IncidentsView。监督席 = IncidentsView 的重构版本。信号流 Tab 仅展示与工单关联的信号（非全部 HealthEvent）。EventCenterView 和 AlertsView 保留独立存在，不做合并。
- Q: 自动开单/愈合后端实现范围？ → A: 最小后端 hook。在现有信号写入路径（HealthEvent 持久化后）加同步 hook，直接用 SQL/JDBC 判断去重/愈合条件并操作工单表。不建新服务/消息队列。去重窗口 5min/冷却窗口 10min 硬编码（后续可配置化）。
- Q: 信号去重指纹如何定义？ → A: eventType + sourceRefId + failureReason。同一任务同原因归并同一工单；同任务不同原因（OOM vs timeout）开不同工单。failureReason 取原始信号中稳定字段（如 EXIT_CODE_-1），不做语义归一化。
- Q: 恢复信号如何匹配待愈合工单？ → A: 精确指纹匹配——开单时存储愈合条件映射（如 TASK_FAILED+taskId=100 → 由 TASK_SUCCESS+taskId=100 愈合），恢复信号到达时按映射匹配。需在工单表新增 heals_by_type + heals_by_ref_id 列（或等效 JSON 列）存储愈合条件。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 监督席主视图：信号总览与工单队列 (Priority: P1)

数据运维人员打开监督席，在一个统一视图中看到两个核心面板——**原始信号流**（系统实时产生的异常事件）和**故障工单队列**（由信号自动生成、待处理的工单），通过页面内下划线式 Tab 切换。每个面板都清晰展示关键信息：严重程度、来源任务/工作流、发生时间、当前状态。

**Why this priority**: 监督席是运维人员的"第一眼"入口。统一信号与工单视图消除当前 EventCenter 和 Incidents 两个分离页面的碎片感，运维人员无需来回跳转即可掌握系统健康全貌。

**Independent Test**: 打开监督席，默认看到信号流面板；切换到工单队列 Tab，看到活跃工单列表。可通过 seed 数据或 mock API 独立验证。

**Acceptance Scenarios**:

1. **Given** 系统有活跃异常信号和工单，**When** 运维人员打开监督席，**Then** 默认显示"信号流"Tab，展示按时间倒序排列的异常事件列表，每条显示类型标签、严重度标记、可读摘要和时间戳。
2. **Given** 监督席已加载，**When** 运维人员点击"工单队列"Tab，**Then** 切换为下划线式激活态，展示活跃工单卡片列表，每张卡片显示严重度、标题、状态、发生次数、SLA 倒计时。
3. **Given** 信号流面板有数据，**When** 运维人员查看一条 TASK_FAILED 信号，**Then** 可以看到可读摘要如"FLINK Streaming Savepoint (062) 执行失败（EXIT_CODE_-1）"及精确到秒的时间戳。
4. **Given** 信号流或工单队列为空，**When** 页面加载完成，**Then** 显示空态占位（图标 + 提示文字），而非空白区域。

---

### User Story 2 - 工单时间线抽屉（DAG/日志同款风格） (Priority: P1)

运维人员点击一张工单卡片的"查看时间线"按钮，弹出一个与查看 DAG、查看日志详情**相同风格的抽屉**——通过侧面板在右侧滑出，展示该工单从信号触发→自动开单→状态变更→人工操作→愈合关闭的完整事件时间线。抽屉内的滚动区域使用 DwScroll 组件（OverlayScrollbars 细条浮叠），与 DAG 详情面板一致。

**Why this priority**: 当前 TimelineDrawer 是固定宽度的右侧浮层（`fixed right-0 w-80`），与 DagDialog 的侧面板风格不一致——用户期望一致的交互体验。重构为统一风格是本次 UI 重构的核心目标之一。

**Independent Test**: 在工单队列中点击任意工单的"时间线"按钮，抽屉从右侧滑入，样式与 DAG 弹窗侧面板一致；可通过已有 IncidentDetail API 独立验证。

**Acceptance Scenarios**:

1. **Given** 工单队列中有一张活跃工单，**When** 运维人员点击"时间线"按钮，**Then** 弹出与 DAG 弹窗同风格的 Dialog，右侧面板滑入展示时间线条目列表，每条包含：事件类型图标、操作者（系统/人）、描述、精确时间戳。
2. **Given** 时间线抽屉已打开，**When** 时间线条目超过可视区域，**Then** 使用 DwScroll（OverlayScrollbars）细条浮叠滚动，滚动条不与面板边框重合，无原生滚动条箭头。
3. **Given** 时间线抽屉已打开，**When** 运维人员点击面板外区域或关闭按钮，**Then** 抽屉平滑关闭，回到工单队列视图。
4. **Given** 工单时间线数据加载中，**When** 抽屉面板渲染，**Then** 显示 LoadingState 居中加载态（不小于 1s 避免闪烁），而非空白或纯文字"加载中"。
5. **Given** 工单没有任何时间线记录，**When** 抽屉打开，**Then** 显示空态（图标 + "暂无时间线记录"），而非空白面板。

---

### User Story 3 - 异常自动开单与自动愈合 (Priority: P2)

系统持续接收原始信号（TASK_FAILED、SLA_BREACH、NODE_OFFLINE 等）。当同类型信号在短时间窗口内达到阈值，系统**自动创建一张故障工单**——无需人工干预。当信号源恢复（如任务重跑成功、节点重新上线），系统**自动将关联工单标记为已愈合**（RESOLVED），并记录愈合时间与原因。

**Why this priority**: 自动开单消除人工盯盘负担，自动愈合避免工单积压。这是"监督席"区别于普通事件列表的核心价值——从被动查看到主动治理。但由于依赖信号采集和规则引擎后端能力，优先级略低于前端 UI 重构。

**Independent Test**: 通过模拟信号注入触发自动开单（发一条 TASK_FAILED 信号→验证工单自动创建），再注入恢复信号触发自动愈合（发一条 TASK_SUCCESS→验证工单自动 RESOLVED）。可独立测试开单/愈合两条路径。

**Acceptance Scenarios**:

1. **Given** 系统收到一条 HIGH 严重度的 TASK_FAILED 信号，**When** 该信号对应的来源（taskInstanceId + failureReason）在 5 分钟内没有已存在的 OPEN 工单，**Then** 系统自动创建一张新工单，状态为 OPEN，严重度与信号一致，标题包含任务名称和失败原因。
2. **Given** 系统收到一条与已有 OPEN 工单匹配的重复信号（同 taskId + 同 failureReason），**When** 该工单尚在 OPEN 状态，**Then** 不创建新工单，仅递增已有工单的 occurrenceCount。
3. **Given** 一张由 TASK_FAILED 信号触发的 OPEN 工单，**When** 系统收到同任务的 TASK_SUCCESS 信号，**Then** 工单自动转为 RESOLVED 状态，记录 resolvedAt 时间戳和愈合原因。
4. **Given** 一张由 NODE_OFFLINE 信号触发的 OPEN 工单，**When** 系统收到同节点的 NODE_ONLINE 信号，**Then** 工单自动转为 RESOLVED 状态。
5. **Given** 一张工单已被人工静默（SUPPRESSED），**When** 系统收到匹配的恢复信号，**Then** 工单不自动愈合（静默状态优先于自动愈合）。

---

### User Story 4 - 前端组件设计规范对齐 (Priority: P2)

监督席页面及所有子组件必须使用项目公共组件目录已定义的规范组件，消除当前的手写 Tab、裸 overflow-auto、自造下拉等违规用法。具体包括：Tab 切换使用下划线式 `Tabs` 组件；所有可滚动区域使用 `DwScroll`；工单卡片使用 `Card` 容器 + `--card-spacing` token；状态标记使用 `Badge` 语义变体；数据加载使用 `LoadingState`。

**Why this priority**: 用户明确指出"前端组件大量的不符合前端设计规范"。这是本次重构的硬性质量门——不通过设计规范校验不得合并。

**Independent Test**: 逐条对照 `specs/037-shared-ui-kit/contracts/reuse-first-checklist.md` 检查监督席所有组件：Tabs/DwScroll/Card/Badge/LoadingState 是否全部使用规范组件；是否存在手写 `overflow-auto`、手写 `role="tablist"`、硬编码内边距等违规。

**Acceptance Scenarios**:

1. **Given** 监督席页面的 Tab 导航，**When** 审查其实现，**Then** 使用 `components/ui/tabs.tsx` 的 `<Tabs>` + `<TabsList>` + `<TabsTrigger>` 组件，不存在手写 `role="tablist"` + `after:absolute` 内联下划线。
2. **Given** 监督席任意可滚动区域（信号列表、工单列表、时间线抽屉内容），**When** 审查其实现，**Then** 使用 `DwScroll` 组件包裹，不存在手写 `overflow-y: auto` 或 `overflow-auto` + WebKit 滚动条伪元素。
3. **Given** 工单卡片容器，**When** 审查其实现，**Then** 使用 `Card` 组件，内边距走 `--card-spacing` token，不出现硬编码 `p-4`/`p-5`/`20px` 等魔法数值。
4. **Given** 信号严重度、工单状态等标记，**When** 审查其实现，**Then** 使用 `Badge` 组件的 `success`/`warning`/`destructive`/`info` 语义变体，不手写颜色 class。
5. **Given** 任何异步数据加载中的区域，**When** 审查其实现，**Then** 使用 `LoadingState` 组件（`mode="centered"` 或 `mode="overlay"`），不出现手写"加载中..."纯文字。
6. **Given** 时间线抽屉内容区域，**When** 滚动条可见，**Then** 使用 OverlayScrollbars 绘制、4px 无箭头细条浮叠、不与容器边框重叠。

---

### User Story 5 - 原始信号详情展示 (Priority: P3)

运维人员在信号流面板点击一条信号，展开或弹出该信号的完整 JSON 原始数据（contextJson），以格式化等宽字体呈现，方便排查问题根因。

**Why this priority**: 原始信号详情是高级运维排查的辅助工具，日常使用频率低于工单管理和时间线查看，但提供关键的透明度。

**Independent Test**: 在信号流中点击任意信号条目，可看到格式化 JSON 原始数据；可独立于工单功能测试。

**Acceptance Scenarios**:

1. **Given** 信号流面板中有一条 TASK_FAILED 信号，**When** 运维人员点击该信号，**Then** 在时间线抽屉风格的面板中展示信号完整 JSON，等宽字体、语法缩进格式化。
2. **Given** 信号的 contextJson 为空，**When** 运维人员点击该信号，**Then** 面板显示"无原始上下文数据"，不展示空白 JSON 或报错。

---

### Edge Cases

- 当信号到达速率极高时（如短时间内同一任务反复失败），系统如何避免创建重复工单？（去重逻辑：同 sourceRefId + 同 fingerprint → occurrenceCount 递增，不重复开单）
- 当工单自动愈合后又收到同源失败信号，系统如何处理？（已有 RESOLVED 工单在冷却窗口内 → 递增计数但不重新 OPEN；超出冷却窗口 → 创建新工单）
- 当信号包含的 taskInstanceId 或 workflowInstanceId 无效/已删除时，工单依然创建但深链不可用——前端需优雅降级（隐藏深链按钮而非报错）
- 时间线条目数量极大（如超过 200 条）时，抽屉内 DwScroll 虚拟滚动或分页加载，避免性能问题
- 用户在工单队列中快速切换 Tab 时，API 请求需正确取消（abort controller），避免数据竞态

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统必须在现有 `IncidentsView`（监督席）基础上重构为统一视图，包含"信号流"和"工单队列"两个子面板，通过标准 Tabs 组件切换。现有的 `EventCenterView` 和 `AlertsView` 保持不变（不合并、不移除）。
- **FR-002**: 信号流面板必须按时间倒序展示系统异常信号，每条信号显示类型标签、严重度徽章、可读摘要、时间戳，支持按事件类型和严重度筛选。
- **FR-003**: 工单队列面板必须展示活跃工单列表（OPEN/MITIGATING 状态优先）和近期已解决工单（降权显示），每张工单卡片包含严重度、标题、状态、发生次数、SLA 倒计时、操作按钮。
- **FR-004**: 系统必须在收到异常信号时自动评估开单条件：以 eventType + sourceRefId + failureReason 为去重指纹，在 5 分钟窗口内无 OPEN 工单时创建新工单；存在则递增 occurrenceCount。不同 failureReason 的同源信号开不同工单。
- **FR-005**: 系统必须在收到恢复信号时自动评估愈合条件：按开单时存储的愈合条件映射（healByType + healByRefId）匹配 OPEN/MITIGATING 工单，匹配成功则自动转为 RESOLVED，记录 resolvedAt 和触发恢复信号引用。恢复信号本身无 failureReason 字段，靠开单时预存的映射桥接。
- **FR-006**: 工单时间线抽屉必须使用与 DAG 弹窗/日志详情一致的 UI 模式：Dialog 容器 + 右侧面板滑入，而非固定定位的 div 浮层。
- **FR-007**: 时间线抽屉内的滚动区域必须使用 DwScroll 组件（OverlayScrollbars），4px 无箭头细条浮叠，不与面板边框重合。
- **FR-008**: 监督席页面所有 UI 原语必须使用项目设计系统公共组件目录中已注册的规范组件（Tab 导航、滚动容器、卡片容器、状态徽章、加载态、下拉选择器等），不得手写替代实现。具体对照清单见 `specs/037-shared-ui-kit/contracts/reuse-first-checklist.md`。
- **FR-009**: 信号条目支持点击展开，展示该信号的完整原始 JSON 数据（contextJson），等宽字体格式化呈现。
- **FR-010**: 工单卡片必须提供"重跑"操作（复用现有 rerunIncident API），按返回 outcome 分流提示（EXECUTED/PENDING_APPROVAL/REJECTED）。
- **FR-011**: 工单卡片必须提供"静默"操作（复用现有 suppressIncident API），打开原因必填弹窗。
- **FR-012**: 工单卡片必须提供"添加备注"操作（复用现有 addIncidentNote API），打开文本输入弹窗。
- **FR-013**: 信号流和工单队列必须支持自动刷新（默认 15s 轮询），使用 ViewRefreshControl 统一刷新入口。

### Key Entities *(include if feature involves data)*

- **原始信号 (Signal/HealthEvent)**: 系统各组件产生的异常事件。属性：类型（TASK_FAILED/SLA_BREACH/NODE_OFFLINE 等）、严重度、来源引用（taskId/workflowInstanceId）、失败原因（failureReason）、上下文 JSON、发生时间。去重指纹 = eventType + sourceRefId + failureReason（同任务同原因归并，不同原因独立工单）。
- **故障工单 (Incident)**: 由信号自动生成或人工创建的治理单元。属性：状态（OPEN/MITIGATING/RESOLVED/SUPPRESSED）、严重度、标题、来源引用、去重指纹（eventType+sourceRefId+failureReason）、愈合条件映射（healByType+healByRefId，开单时存储）、发生次数、SLA 时间预算、静默原因、解决时间。关联：多条时间线条目。
- **时间线条目 (Timeline Entry)**: 工单生命周期中的事件记录。属性：序号、类型（SIGNAL/STATE_CHANGE/ACTION/APPROVAL/NOTE）、操作者（system 或用户名）、负载 JSON、创建时间。关联：属于一张工单。
- **订阅规则 (Subscription)**: 信号到通知渠道的绑定规则（复用现有 EventSubscription），本次不扩展。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 运维人员从打开监督席到定位到目标工单的时间不超过 10 秒（通过筛选 + 时间线查看）。
- **SC-002**: 监督席页面所有 UI 原语 100% 使用设计系统已注册的公共组件，零处手写替代实现（如手写 tab 导航、手写滚动条样式、裸 overflow 容器、自造状态徽章）。
- **SC-003**: 异常信号到达后 30 秒内自动创建对应工单（端到端：信号入库 → 开单评估 → 工单可见）。
- **SC-004**: 恢复信号到达后 30 秒内自动愈合对应工单（端到端：恢复信号入库 → 愈合评估 → 工单状态更新）。
- **SC-005**: 时间线抽屉打开到首屏时间线条目可见不超过 1 秒（加载态最小 1s 避免闪烁）。
- **SC-006**: 监督席所有可滚动区域使用统一细条浮叠式滚动条（无箭头、不占布局空间、不与容器边框重叠），零原生浏览器滚动条出现。

## Assumptions

- 后端已有 event-center 和 incident 的 API 基础（`event-center-api.ts`、`incident-api.ts`），本次前端重构复用现有 API 契约，必要时后端做适配性调整（如新增自动开单/愈合的触发逻辑）。
- 信号去重窗口默认 5 分钟，工单愈合后冷却窗口默认 10 分钟——这些参数可在后续通过配置调整。
- 自动开单/愈合在后端通过现有信号写入路径的同步 hook 实现（SQL/JDBC 直接判断），不新建独立服务或消息队列。去重窗口 5 分钟、愈合后冷却窗口 10 分钟硬编码。
- 本功能作用于单个项目（tenant/project 隔离），复用现有 ProjectContext 和 API 鉴权。
- 订阅管理（EventSubscription）作为现有功能保留，在监督席中作为辅助入口（如"创建订阅"按钮跳转），不作为本次重构重点。
- 设计规范以 `frontend/DESIGN.md` 和 `specs/037-shared-ui-kit/` 为准，所有 UI 决策优先复用公共组件目录。
