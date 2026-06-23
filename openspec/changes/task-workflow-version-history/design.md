## Context

任务和工作流的版本发布基础设施（版本表、实体、Repository、发布 API）已在后端就绪。`GET /api/tasks/{id}` 已通过 `TaskDetail` DTO 返回 `versions[]`，但前端未消费。`GET /api/workflows/{id}` 仍返回扁平 `WorkflowDef`，无版本列表。两个实体都没有回滚 API。

前端 `TaskEditorPane` 右侧配置栏以平铺方式展示所有字段，随着调度参数、替换预览等功能加入已显拥挤。`WorkflowCanvasPane` 无任何右侧栏。

## Goals / Non-Goals

**Goals:**
- 用户能在编辑器/画布内直接浏览、对比、回滚历史版本，无需离开数据开发 IDE
- 任务与工作流版本历史体验对称一致
- 右侧栏结构化为 tab 布局，为后续扩展预留空间
- 回滚安全：覆盖草稿前必须用户确认

**Non-Goals:**
- 不做版本级别的分支/合并（git-like）
- 不做版本间自动 diff 摘要（只显示原始 diff）
- 不做版本备注编辑（历史版本只读）
- 不做版本导出/下载
- 不改变现有发布流程（发布仍为冻结快照 → 上线）

## Decisions

### D1: 右侧栏 Tab 化而非折叠面板

**选择**：右侧栏顶部放 [配置 | 版本历史] 两个 tab，切换时替换整个面板内容。

**替代方案**：
- 折叠面板（Accordion）：垂直空间不够，版本列表需要全高
- 独立子 Tab（与 canvas/editor 并列）：版本浏览是编辑器内的辅助操作，不值得占一个完整子 Tab

**理由**：tab 切换最直观，右侧栏 320px 宽度固定，tab 切换让每个功能区独占全高。

### D2: 版本历史面板组件共享，数据源通过 props 注入

**选择**：一个 `VersionHistoryPanel` 通用组件，接收 `versions[]`、`onView(version)`、`onRollback(version)`、`onDiff(v1, v2)` 回调。任务和画布各自包装一个薄壳注入数据源和操作。

**替代方案**：
- 任务和工作流各写一套：重复代码，UI 不一致

**理由**：两实体版本列表结构完全同构（versionNo/publishedBy/publishedAt/remark），共享组件减少代码并保证体验一致。

### D3: 版本详情用 Dialog，不在面板内展开

**选择**：点击"查看"弹出 Dialog，Dialog 内只读展示该版本完整快照（代码 + 配置）。

**替代方案**：
- 面板内展开：320px 太窄，代码展示效果差
- 新开子 Tab：太重，版本查看是临时操作

**理由**：Dialog 有足够空间展示代码，且不干扰当前编辑上下文。

### D4: Diff 对比使用 Monaco DiffEditor

**选择**：选两个版本后弹出 Dialog，内嵌 Monaco DiffEditor（左旧右新）。已有 Monaco 依赖（`@monaco-editor/react`）。

**替代方案**：
- 纯文本 diff（`diff` 库）：没有语法高亮，体验差
- 后端生成 diff 文本：前端 Monaco DiffEditor 更直观

**理由**：Monaco DiffEditor 是代码对比的行业标准，项目已有 Monaco 依赖无额外成本。

### D5: 回滚 API 独立端点，恢复为草稿

**选择**：
- `POST /api/tasks/{id}/rollback` body `{ "versionNo": N }`
- `POST /api/workflows/{id}/rollback` body `{ "versionNo": N }`
- 后端把快照内容写回 `task_def` / `workflow_def` + nodes/edges，置 `hasDraftChange=1`
- 不改 `currentVersionNo`（发布时才更新）

**替代方案**：
- 复用 `PUT /api/tasks/{id}` 让前端拼快照数据发回：语义不清，前端负担大
- 直接发布回滚版本：破坏发布流程的显式性

**理由**：独立端点语义清晰；恢复为草稿让用户可以检查后再发布，安全。

### D6: WorkflowDetail DTO 与 TaskDetail 对称

**选择**：新增 `WorkflowDetail` record（`workflow` + `versions[]`），改造 `GET /api/workflows/{id}` 返回此 DTO。前端同步适配。

**替代方案**：
- 新增独立的 `GET /api/workflows/{id}/versions` 端点：多一次 HTTP 请求

**理由**：与 Task API 对称，前端一次请求拿到所有数据，组件接口统一。

### D7: 工作流配置面板字段

**选择**：工作流配置 tab 包含：名称、描述、调度类型（cron/事件）、cron 表达式、优先级、超时。与 `WorkflowDef` 可编辑字段对齐。

**替代方案**：
- 只放名称和描述：太少，用户仍无法在画布内改调度
- 全量字段含 owner 等：太杂

**理由**：覆盖用户最常用的工作流级配置，避免频繁跳转。

## Risks / Trade-offs

- **[API 破坏性变更] `GET /api/workflows/{id}` 响应结构变化** → 前端必须同步适配；CLI 如消费此端点也需检查。缓解：先改前端再部署后端，或在后端做兼容（`?includeVersions=true` 参数控制新旧格式）。决定：直接切换，因为前端是唯一消费者，CLI 不消费此端点。
- **[回滚覆盖风险] 用户误回滚丢失当前草稿** → 前端弹窗明确告知"当前草稿将被覆盖"，列出版本号对比。
- **[Monaco DiffEditor 体积] 大脚本 diff 渲染性能** → Monaco DiffEditor 对 4000 行以内的代码性能无问题（任务 content 上限 VARCHAR(4000)）。工作流 DAG diff 用 JSON 结构对比而非 Monaco。
- **[Tab 状态丢失] 切换 tab 后配置表单状态** → 配置 tab 的表单状态由 React state 管理，切换 tab 不卸载组件（CSS 隐藏而非条件渲染），与现有编辑器 keep-alive 策略一致。
