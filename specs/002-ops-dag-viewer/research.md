# Research: 运维任务流 DAG 查看器

**Feature**: 002-ops-dag-viewer | **Date**: 2026-06-26

## 1. 后端：如何获取已发布 DAG

### Decision
新增 `GET /api/workflows/{id}/published-dag` 端点，从 `workflow_def_version` 表的 `dag_snapshot_json` 列读取当前发布版本的 DAG 快照，反序列化为 `DagView` 格式返回。

### Rationale
- 现有 `GET /api/workflows/{id}/dag` 返回的是**草稿 DAG**（`workflow_node` + `workflow_edge` 表），不适合运维查看线上版本的需求
- `WorkflowDefVersion.dagSnapshotJson` 已在发布时存储了完整的 DAG 快照（节点+边+布局），无需新建数据表
- 反序列化后直接映射到前端已有的 `DagView` 接口，前端零类型改动

### Alternatives Considered
| 方案 | 评估 |
|------|------|
| A. 在 `GET /api/workflows/{id}` 的 `WorkflowDetail.versions` 中查找 latest version | 前端需额外解析 JSON 和版本匹配逻辑；`dagSnapshotJson` 是字符串而非结构化对象，需后端解析 |
| B. 新增 `GET /api/workflows/{id}/versions/{versionNo}/dag` | 更通用（支持查看历史版本 DAG），但当前需求只需最新发布版，过度设计 |
| C. 直接返回 `WorkflowDagSnapshot` JSON | 与前端已有的 `DagView` 类型不一致，需额外适配层 |

**选择方案 B 的精简版——只返回最新发布版**：当前需求明确限定"查看运维发布版"，不需要版本选择。端点命名 `published-dag` 语义清晰。未来如需历史版本查看可扩展。

### Implementation Sketch
```java
// WorkflowController.java
@GetMapping("/{id}/published-dag")
public ApiResponse<DagView> readPublishedDag(@PathVariable Long id) {
    return ApiResponse.ok(workflowService.readPublishedDag(id));
}

// WorkflowService.java
public DagView readPublishedDag(Long workflowId) {
    WorkflowDef wf = workflowDefRepository.findById(workflowId).orElseThrow(...);
    if (!"ONLINE".equals(wf.status()) || wf.currentVersionNo() == null) {
        throw new BizException("workflow.not_online_or_unpublished");
    }
    WorkflowDefVersion ver = workflowDefVersionRepository
        .findByWorkflowIdAndVersionNo(workflowId, wf.currentVersionNo())
        .orElseThrow(...);
    WorkflowDagSnapshot snap = objectMapper.readValue(ver.dagSnapshotJson(), ...);
    return DagView.from(workflowId, ver.versionNo(), wf.status(), snap);
}
```

---

## 2. 前端：DAG 弹框组件设计

### Decision
创建独立的 `DagViewerDialog` 组件，以 `@base-ui/react/dialog` 为弹框原语，内部嵌入只读 `ReactFlow` 实例，复用 `TaskNode`/`VirtualNode` 节点组件和 $xyflow/react 的 `fitView` 布局。

### Rationale
- 现有 `workflow-canvas-view.tsx` 是高度耦合的编辑态画布（含工具栏、目录树、版本面板、运行对话框等），不适合作为弹框复用
- `lineage-graph.tsx` 证明了只读 ReactFlow 的可行性：`nodesDraggable={false}` + `nodesConnectable={false}` + 无 `onNodesChange`/`onEdgesChange`
- 复用 `TaskNode`/`VirtualNode` 保持视觉一致性，运维人员看到的 DAG 节点与开发画布中的一致

### Alternatives Considered
| 方案 | 评估 |
|------|------|
| A. 复用 `workflow-canvas-view.tsx` 加 `readOnly` prop | 组件职责过量，引入只读模式会使已有逻辑更复杂；且该组件有工具栏、侧边栏、运行对话框等交互元素，需大量条件判断隐藏 |
| B. 跳转到新 Tab（如 `workflow-canvas?readOnly=true`） | 不符合"不要跳转"的需求，用户需来回切换 Tab |
| C. `Dialog` 内嵌 iframe 指向独立只读页面 | 不必要的复杂度，iframe 通信开销大，样式隔离困难 |

**选择方案 A 的变体——独立组件提取**：将 `TaskNode` 和 `VirtualNode` 提取为共享节点组件（或直接在弹框组件内引用），弹框组件内部创建独立的只读 `ReactFlow`。

### Read-Only ReactFlow Configuration
```typescript
<ReactFlow
  nodes={dagNodes}
  edges={dagEdges}
  nodeTypes={NODE_TYPES}
  nodesDraggable={false}
  nodesConnectable={false}
  fitView
  fitViewOptions={{ padding: 0.2 }}
>
  <Background />
  <Controls showInteractive={false} />
</ReactFlow>
```

关键差异于编辑态画布：不注册 `onNodesChange`/`onEdgesChange`/`onConnect`/`onDrop`/`onDragOver`，不设置 `deleteKeyCode`，不渲染 `MiniMap`（弹框空间有限）。

### Node Component Extraction
`TaskNode` 和 `VirtualNode` 当前定义在 `workflow-canvas-view.tsx` 内（分别为 178-230 和 232-256 行）。需要：
1. 提取到 `frontend/components/workspace/nodes/task-node.tsx` 和 `virtual-node.tsx`
2. 节点内部有条件渲染：编辑态显示 ContextMenu，只读态不显示
3. 弹框引用提取后的节点组件

---

## 3. 按钮行为变更

### Decision
修改 `periodic-workflows-panel.tsx` 和 `manual-workflows-panel.tsx` 中的"查看 DAG"按钮 `onClick` 处理：从 `open("workflow-canvas", ...)` 改为 `setSelectedWorkflow(w)` 打开 `DagViewerDialog`。

### Rationale
- 当前按钮调用 `useWorkspaceStore.open("workflow-canvas", { workflowId, name })`，这会触发 Tab 切换/创建
- 改为打开弹框：在 panel 组件内维护 `selectedWorkflow` state，条件渲染 `<DagViewerDialog workflowId={...} open={...} onOpenChange={...} />`

### Status Gate
仅 `status === "ONLINE"` 的 workflow 才显示"查看 DAG"按钮。代码中需检查 `w.status`。

---

## 4. 弹框尺寸

### Decision
弹框宽度 `90vw`，高度 `90vh`（通过 DialogContent 的 className 覆盖 `max-w-lg` 默认值）。

### Rationale
- 规格建议 80vw × 85vh。实际实现优先最大化可视面积——`90vw × 90vh` 在 1920×1080 分辨率下为 1728×972px，足以容纳大型 DAG
- 使用 `max-w-[90vw] max-h-[90vh]` 配合 `w-[90vw] h-[90vh]`，响应不同分辨率

---

## 5. 错误与边界状态

### Decision
- **未发布/草稿任务流**: 前端判 `status !== "ONLINE"` — 不渲染按钮/置灰
- **后端无发布版本**: 返回 `BizException("workflow.not_online_or_unpublished")` — 前端 toast 提示
- **空 DAG**: 后端正常返回 0 节点；前端弹框内显示 empty state（带图示和文案）
- **网络错误**: 弹框内显示错误提示 + 重试按钮（调用 `loadDag` 重新 fetch）
- **过期数据**: 弹框内 DAG 是快照，打开后不自动刷新（规格已明确）

### i18n Keys Required
| Key | zh-CN | en-US |
|-----|-------|-------|
| `dagViewer.title` | 任务流 DAG - {name} | Workflow DAG - {name} |
| `dagViewer.empty` | 该任务流发布版本不包含任何节点 | This workflow's published version contains no nodes |
| `dagViewer.error` | 加载 DAG 失败 | Failed to load DAG |
| `dagViewer.retry` | 重试 | Retry |
| `dagViewer.versionInfo` | 发布版本 v{versionNo}，{publishedAt} | Published v{versionNo}, {publishedAt} |
