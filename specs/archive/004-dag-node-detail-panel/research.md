# Research: DAG 节点详情侧面板

**Created**: 2026-06-26

## 1. 后端：任务版本详情数据获取

### Decision
在 `OpsController` 中新增端点 `GET /api/ops/workflows/{workflowId}/nodes/{nodeKey}/detail`，由 `OpsService` 委托 `WorkflowService` 读取发布快照中的节点元数据（taskId + taskVersionNo），再通过 `TaskDefVersionRepository.findByTaskIdAndVersionNo()` 获取 `TaskDefVersion` 完整记录，返回节点任务版本详情。

同时扩展 `DagNodeDto`，增加 `taskVersionNo` 字段，使 `published-dag` API 的 `DagView` 中每个节点都携带发布版本号。前端收到后可直接用 taskId + taskVersionNo 构造详情请求。

### Rationale
- `TaskDefVersionRepository.findByTaskIdAndVersionNo(taskId, versionNo)` 已存在，无需新建仓储方法。
- `TaskDefVersion` 实体已包含所有需要展示的字段：`name`, `type`, `content`（代码）, `paramsJson`（配置参数）, `versionNo`, `publishedAt`, `datasourceId`, `targetDatasourceId`, `timeoutSec`, `retryMax`。
- `WorkflowDagSnapshot.Node` 已包含 `taskVersionNo`，但在 `readPublishedDag()` 中转换到 `DagNodeDto` 时被丢弃——仅需补传即可，改动极小。
- 放在 `OpsController` 而非 `TaskController` 的理由：这是运维视角的操作，与 Ops 中心其他端点（`/api/ops/...`）保持一致。

### Alternatives Considered
- **A) `GET /api/tasks/{taskId}/versions/{versionNo}`**：通用 API 放在 `TaskController` 下。不够语义化——这是 Ops DAG 场景专用的查询，放在 `/api/ops/` 下更清晰。
- **B) 在 `published-dag` 响应中内嵌全部节点详情**：一次性返回所有数据。问题是 DAG 可能有 50+ 节点，每个节点的代码可能 500+ 行，全量返回造成不必要的传输和解析开销。按需加载更合理。
- **C) 复用现有 `GET /api/tasks/{id}` 返回 `TaskDetail`**：该端点返回任务当前版本 + 全部历史版本列表，不适用于查看发布时冻结的特定版本。

---

## 2. 前端：DagRenderer 只读模式下的节点点击

### Decision
修改 `DagRenderer` 的两个行为：
1. 将 `onNodeClick` 的类型从 `() => void` 更正为 ReactFlow 标准的 `(event: React.MouseEvent, node: Node) => void`。
2. 在 readOnly 模式下**仍然传递** `onNodeClick`（当前代码在 readOnly 时将其设为 `undefined`）。

### Rationale
- `onNodeClick` 是只读通知，不涉及图编辑操作——允许它在 readOnly 模式下触发是安全的。
- ReactFlow 自身的 `onNodeClick` 签名是 `NodeMouseHandler<NodeType>` = `(event, node) => void`。`DagRenderer` 当前的 `() => void` 类型是错误的（可能来自历史简化），修正是必要的。
- 仅需修改 2 行代码（类型声明 + 条件判断），风险极低。

### Alternatives Considered
- **A) 在 TaskNode 组件内部加 onClick**：在节点 div 上添加 `onClick` 并使用 `NodeProps` 透传的 `onClick`。但 `NodeProps.onClick` 的触发范围是整个节点内部区域（包括空白），不如 `onNodeClick` 精确。
- **B) 为 readOnly 提供独立的 `onReadOnlyNodeClick` prop**：增加新 prop 而非复用现有的，避免语义混淆。这会使 API 膨胀——当前 `onNodeClick` 仅用于关闭边缘菜单（非编辑操作），语义上就是只读的。

---

## 3. 前端：右击上下文菜单

### Decision
在 `dag-viewer-dialog.tsx` 层面，通过 `DagRenderer` 新增的 `onNodeContextMenu` prop（ReactFlow 原生支持），在弹框内统一处理右击事件，显示一个位于鼠标位置的 `ContextMenu`（使用 `@base-ui/react/context-menu` 基元，与代码库已有的 context-menu 组件一致）。

不在 `TaskNode` 组件内部通过 `NodeActionsContext` 处理（那是编辑模式专用的路径）。

### Rationale
- ReactFlow 原生提供 `onNodeContextMenu: NodeMouseHandler`，无需在每个节点组件内部添加事件监听。
- 避免在只读模式下引入 `NodeActionsContext`（该上下文的设计意图是为编辑画布提供 run/delete/log 等写操作）。
- 使用项目已有的 `@base-ui/react/context-menu` 基元保持 UI 一致性。
- 菜单仅包含"查看任务详情"一项（VIRTUAL 节点不显示此菜单）。

### Alternatives Considered
- **A) 复用 NodeActionsContext**：在 dag-viewer-dialog 中提供一个最小化的 `NodeActionsContext`，仅包含 `onViewLog`/`onViewDetail`。这是过度耦合——NodeActionsContext 的设计包含 6 个 action 和写操作语义，不适合只读场景。
- **B) 在 TaskNode 内部添加右键监听**：使用原生 `onContextMenu` 事件。这会绕过 ReactFlow 的事件系统，失去统一的节点定位信息。

---

## 4. 前端：可拖拽调整宽度的侧面板

### Decision
复用项目现有的 motion drag-resize 模式（已在 `agent-rail.tsx`、`workflow-canvas-view.tsx`（catalog tree）、`log-panel.tsx` 中使用）。使用 `useMotionValue` + `useTransform` + pointer events 实现零 React 重渲染拖拽。

面板容器结构：
- `dag-viewer-dialog.tsx` 的 body 从单一 `div.flex-1` 改为 `div.flex.flex-row`
- 左侧：`div` 包含 `DagRenderer`（动态宽度：弹框宽度 - 面板宽度）
- 分割线：`div` 作为拖拽把手（`cursor: col-resize`，`onPointerDown` 启动拖拽）
- 右侧：`node-detail-panel.tsx` 组件（`useMotionValue` 控制宽度）

面板宽度：默认 25%（弹框宽度 / 4），最小 280px，最大 33%（弹框宽度 / 3）。

### Rationale
- 零新依赖——motion/react 已在项目中使用。
- 已验证的模式：3 个已有实现均使用完全相同的 motion + pointer event 套路，稳定可靠。
- `useMotionValue` 直接操作 DOM style，不触发 React 重渲染——拖拽 60fps 无卡顿。
- 面板宽度持久化到 `localStorage`（key: `dw.dagViewer.panelWidth`），与项目其他面板的模式一致。

### Alternatives Considered
- **A) allotment / react-resizable-panels**：成熟的库，但引入新依赖增加 bundle size，且与项目其他三处自建实现不一致。
- **B) CSS grid + CSS resize**：浏览器原生 resize 属性。功能和样式受限（无法自定义把手外观，各浏览器行为不一致），不支持 localStorage 持久化。

---

## 5. 前端：代码语法高亮

### Decision
复用项目已有的 Shiki 高亮管道（`components/chat/highlighter.ts`）。面板内代码内容通过 `highlightCode(code, lang)` 生成双主题 HTML，在组件中通过 `dangerouslySetInnerHTML` 渲染。语言映射复用 `highlighter.ts` 中的 `resolveLang()` 函数。

### Rationale
- Shiki 已集成并缓存化（`shikiCache`），无需额外构建或加载步骤。
- 双主题策略（light/dark）通过 CSS 自定义属性切换，无需重新高亮。
- 任务类型到语言的映射：SQL 任务 → `sql`，Python 任务 → `python`，Shell 任务 → `bash`，Java 任务 → `java`，JSON 配置 → `json`。对于未知/无代码类型，展示提示文案。
