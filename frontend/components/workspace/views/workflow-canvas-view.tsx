"use client"

/**
 * 工作流编排画布（workflow-canvas）。
 *
 * 基于 @xyflow/react 的 DAG 可视化编辑器：左侧 task_def 列表拖入建 TASK 节点、
 * 工具栏放置 VIRTUAL 节点、连线建边（本地即时环路检测）、保存草稿（整图 PUT）、发布。
 * 分支/归并由拓扑天然表达（多出边/多入边），无条件分支。
 */
import { useCallback, useEffect, useMemo, useRef, useState } from "react"
import {
  ReactFlow,
  ReactFlowProvider,
  Background,
  Controls,
  MiniMap,
  Handle,
  Position,
  addEdge,
  applyNodeChanges,
  applyEdgeChanges,
  useReactFlow,
  type Node,
  type Edge,
  type Connection,
  type NodeChange,
  type EdgeChange,
  type NodeProps,
} from "@xyflow/react"
import "@xyflow/react/dist/style.css"
import { toast } from "sonner"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  WorkflowSquare01Icon,
  CircleIcon,
  DatabaseIcon,
  Add01Icon,
  FloppyDiskIcon,
  RocketIcon,
} from "@hugeicons/core-free-icons"

import { Button } from "@/components/ui/button"
import { Badge } from "@/components/ui/badge"
import { DropdownSelect } from "@/components/ui/select"
import {
  API_BASE,
  authFetch,
  type ApiResponse,
  type DagView,
  type DagPayload,
  type DagNode,
  type DagEdge,
  type TaskDef,
  type WorkflowDef,
  type WorkflowPage,
} from "@/lib/types"
import { ViewStatus } from "./view-status"

// ─── 节点数据类型 ──────────────────────────────────────────

interface CanvasNodeData extends Record<string, unknown> {
  nodeType: "TASK" | "VIRTUAL"
  taskId: number | null
  label: string
}
type CanvasNode = Node<CanvasNodeData>

const shortId = () =>
  (typeof crypto !== "undefined" && crypto.randomUUID
    ? crypto.randomUUID().slice(0, 8)
    : Math.floor(Math.random() * 1e9).toString(36))

// ─── 自定义节点组件 ────────────────────────────────────────

function TaskNode({ data, selected }: NodeProps<CanvasNode>) {
  return (
    <div
      className={`flex items-center gap-2 rounded-md border bg-card px-3 py-2 text-xs shadow-sm ${
        selected ? "border-primary ring-1 ring-primary" : "border-border"
      }`}
    >
      <Handle type="target" position={Position.Left} />
      <HugeiconsIcon icon={DatabaseIcon} className="size-4 text-primary" />
      <span className="max-w-40 truncate font-medium">{data.label || "任务"}</span>
      <Handle type="source" position={Position.Right} />
    </div>
  )
}

function VirtualNode({ data, selected }: NodeProps<CanvasNode>) {
  return (
    <div
      className={`flex items-center gap-2 rounded-full border border-dashed bg-muted px-3 py-2 text-xs ${
        selected ? "border-primary ring-1 ring-primary" : "border-muted-foreground/40"
      }`}
    >
      <Handle type="target" position={Position.Left} />
      <HugeiconsIcon icon={CircleIcon} className="size-4 text-muted-foreground" />
      <span className="max-w-40 truncate text-muted-foreground">{data.label || "虚拟节点"}</span>
      <Handle type="source" position={Position.Right} />
    </div>
  )
}

// ─── DagView ↔ ReactFlow 映射 ─────────────────────────────

function toFlow(dag: DagView): { nodes: CanvasNode[]; edges: Edge[] } {
  const nodes: CanvasNode[] = dag.nodes.map((n) => ({
    id: n.nodeKey,
    type: n.nodeType === "VIRTUAL" ? "virtual" : "task",
    position: { x: n.posX ?? 0, y: n.posY ?? 0 },
    data: { nodeType: n.nodeType, taskId: n.taskId, label: n.name ?? "" },
  }))
  const edges: Edge[] = dag.edges.map((e) => ({
    id: `${e.fromNodeKey}->${e.toNodeKey}`,
    source: e.fromNodeKey,
    target: e.toNodeKey,
  }))
  return { nodes, edges }
}

function toPayload(version: number | null, nodes: CanvasNode[], edges: Edge[]): DagPayload {
  const dagNodes: DagNode[] = nodes.map((n) => ({
    nodeKey: n.id,
    nodeType: n.data.nodeType,
    taskId: n.data.taskId,
    name: n.data.label,
    posX: Math.round(n.position.x),
    posY: Math.round(n.position.y),
  }))
  const dagEdges: DagEdge[] = edges.map((e) => ({ fromNodeKey: e.source, toNodeKey: e.target }))
  return { version, nodes: dagNodes, edges: dagEdges }
}

/** 在现有边集上加入 source→target 是否成环：从 target 出发能否回到 source。 */
function wouldCreateCycle(edges: Edge[], source: string, target: string): boolean {
  if (source === target) return true
  const adj = new Map<string, string[]>()
  for (const e of edges) {
    if (!adj.has(e.source)) adj.set(e.source, [])
    adj.get(e.source)!.push(e.target)
  }
  const stack = [target]
  const seen = new Set<string>()
  while (stack.length) {
    const cur = stack.pop()!
    if (cur === source) return true
    if (seen.has(cur)) continue
    seen.add(cur)
    for (const nxt of adj.get(cur) ?? []) stack.push(nxt)
  }
  return false
}

// ─── 画布内部（须在 ReactFlowProvider 内）──────────────────

function CanvasInner({ initialWorkflowId }: { initialWorkflowId?: number }) {
  const [workflows, setWorkflows] = useState<WorkflowDef[]>([])
  const [tasks, setTasks] = useState<TaskDef[]>([])
  const [selectedId, setSelectedId] = useState<number | null>(initialWorkflowId ?? null)
  const [dagVersion, setDagVersion] = useState<number | null>(null)
  const [status, setStatus] = useState<string>("DRAFT")
  const [hasDraft, setHasDraft] = useState(false)
  const [nodes, setNodes] = useState<CanvasNode[]>([])
  const [edges, setEdges] = useState<Edge[]>([])
  const [dirty, setDirty] = useState(false)
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)

  const { screenToFlowPosition } = useReactFlow()
  const wrapperRef = useRef<HTMLDivElement>(null)
  const nodeTypes = useMemo(() => ({ task: TaskNode, virtual: VirtualNode }), [])

  // 初次加载工作流列表 + 任务列表
  useEffect(() => {
    Promise.all([
      authFetch(`${API_BASE}/api/workflows?size=100`, { cache: "no-store" })
        .then((r) => r.json() as Promise<ApiResponse<WorkflowPage>>)
        .then((j) => (j.code === 0 ? j.data?.content ?? [] : [])),
      authFetch(`${API_BASE}/api/tasks?size=100`, { cache: "no-store" })
        .then((r) => r.json() as Promise<ApiResponse<WorkflowPage & { content: TaskDef[] }>>)
        .then((j) => (j.code === 0 ? (j.data?.content as unknown as TaskDef[]) ?? [] : [])),
    ])
      .then(([wfs, tks]) => {
        setWorkflows(wfs)
        setTasks(tks)
        if (selectedId == null && wfs.length > 0) setSelectedId(wfs[0].id)
      })
      .finally(() => setLoading(false))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // 加载选中工作流的 DAG
  const loadDag = useCallback((id: number) => {
    setBusy(true)
    authFetch(`${API_BASE}/api/workflows/${id}/dag`, { cache: "no-store" })
      .then((r) => r.json() as Promise<ApiResponse<DagView>>)
      .then((j) => {
        if (j.code === 0 && j.data) {
          const { nodes: ns, edges: es } = toFlow(j.data)
          setNodes(ns)
          setEdges(es)
          setDagVersion(j.data.version)
          setStatus(j.data.status)
          setHasDraft(j.data.hasDraftChange === 1)
          setDirty(false)
        } else {
          toast.error(j.message || "读取 DAG 失败")
        }
      })
      .catch(() => toast.error("读取 DAG 失败"))
      .finally(() => setBusy(false))
  }, [])

  useEffect(() => {
    if (selectedId != null) loadDag(selectedId)
  }, [selectedId, loadDag])

  // ── ReactFlow 变更回调 ──
  const onNodesChange = useCallback((changes: NodeChange<CanvasNode>[]) => {
    setNodes((nds) => applyNodeChanges(changes, nds))
    if (changes.some((c) => c.type === "remove" || (c.type === "position" && c.dragging === false))) {
      setDirty(true)
    }
  }, [])

  const onEdgesChange = useCallback((changes: EdgeChange<Edge>[]) => {
    setEdges((eds) => applyEdgeChanges(changes, eds))
    if (changes.some((c) => c.type === "remove")) setDirty(true)
  }, [])

  const onConnect = useCallback(
    (conn: Connection) => {
      if (!conn.source || !conn.target) return
      setEdges((eds) => {
        if (wouldCreateCycle(eds, conn.source, conn.target)) {
          toast.error("该连线会形成环路，已拒绝")
          return eds
        }
        setDirty(true)
        return addEdge(conn, eds)
      })
    },
    [],
  )

  // ── 拖拽建 TASK 节点 ──
  const onDragOver = useCallback((e: React.DragEvent) => {
    e.preventDefault()
    e.dataTransfer.dropEffect = "move"
  }, [])

  const onDrop = useCallback(
    (e: React.DragEvent) => {
      e.preventDefault()
      const raw = e.dataTransfer.getData("application/dw-task")
      if (!raw) return
      const task = JSON.parse(raw) as { id: number; name: string }
      const position = screenToFlowPosition({ x: e.clientX, y: e.clientY })
      const id = `n-${shortId()}`
      setNodes((nds) => [
        ...nds,
        {
          id,
          type: "task",
          position,
          data: { nodeType: "TASK", taskId: task.id, label: task.name },
        },
      ])
      setDirty(true)
    },
    [screenToFlowPosition],
  )

  // ── 工具栏动作 ──
  const addVirtualNode = useCallback(() => {
    const id = `v-${shortId()}`
    setNodes((nds) => [
      ...nds,
      {
        id,
        type: "virtual",
        position: { x: 60, y: 60 + nds.length * 20 },
        data: { nodeType: "VIRTUAL", taskId: null, label: "虚拟节点" },
      },
    ])
    setDirty(true)
  }, [])

  const createWorkflow = useCallback(() => {
    const name = window.prompt("新建工作流名称")
    if (!name) return
    setBusy(true)
    authFetch(`${API_BASE}/api/workflows`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ name }),
    })
      .then((r) => r.json() as Promise<ApiResponse<WorkflowDef>>)
      .then((j) => {
        if (j.code === 0 && j.data) {
          setWorkflows((ws) => [j.data as WorkflowDef, ...ws])
          setSelectedId(j.data.id)
          toast.success("已创建工作流草稿")
        } else {
          toast.error(j.message || "创建失败")
        }
      })
      .finally(() => setBusy(false))
  }, [])

  const saveDraft = useCallback(() => {
    if (selectedId == null) return
    setBusy(true)
    const payload = toPayload(dagVersion, nodes, edges)
    authFetch(`${API_BASE}/api/workflows/${selectedId}/dag`, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    })
      .then((r) => r.json() as Promise<ApiResponse<DagView>>)
      .then((j) => {
        if (j.code === 0 && j.data) {
          setDagVersion(j.data.version)
          setHasDraft(j.data.hasDraftChange === 1)
          setDirty(false)
          toast.success("草稿已保存")
        } else if (j.code === 409) {
          toast.error("工作流已被他人修改，请刷新后重试")
        } else {
          toast.error(j.message || "保存失败")
        }
      })
      .catch(() => toast.error("保存失败"))
      .finally(() => setBusy(false))
  }, [selectedId, dagVersion, nodes, edges])

  const publish = useCallback(() => {
    if (selectedId == null) return
    if (dirty) {
      toast.error("有未保存改动，请先保存草稿")
      return
    }
    setBusy(true)
    authFetch(`${API_BASE}/api/workflows/${selectedId}/publish`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({}),
    })
      .then((r) => r.json() as Promise<ApiResponse<WorkflowDef>>)
      .then((j) => {
        if (j.code === 0 && j.data) {
          setStatus(j.data.status)
          setHasDraft(false)
          toast.success(`已发布 v${j.data.currentVersionNo}`)
        } else {
          // 含环路拒绝（409）：编辑态保留不丢
          toast.error(j.message || "发布失败")
        }
      })
      .catch(() => toast.error("发布失败"))
      .finally(() => setBusy(false))
  }, [selectedId, dirty])

  if (loading) return <ViewStatus loading />

  return (
    <div className="flex h-full flex-col">
      {/* 工具栏 */}
      <div className="flex flex-wrap items-center gap-2 p-3">
        <HugeiconsIcon icon={WorkflowSquare01Icon} className="text-primary" />
        <h1 className="text-sm font-medium">工作流编排</h1>
        <DropdownSelect
          value={selectedId != null ? String(selectedId) : ""}
          onChange={(v) => setSelectedId(Number(v))}
          placeholder="选择工作流"
          className="w-48"
          options={workflows.map((w) => ({ value: String(w.id), label: w.name }))}
        />
        <Button size="sm" variant="outline" onClick={createWorkflow} disabled={busy}>
          <HugeiconsIcon icon={Add01Icon} className="size-4" /> 新建工作流
        </Button>

        <div className="ml-auto flex items-center gap-2">
          {status === "ONLINE" && <Badge variant="success">已上线</Badge>}
          {(dirty || hasDraft) && (
            <Badge variant="outline" className="text-amber-600">
              {dirty ? "未保存改动" : "有未发布改动"}
            </Badge>
          )}
          <Button size="sm" variant="outline" onClick={addVirtualNode} disabled={selectedId == null || busy}>
            <HugeiconsIcon icon={CircleIcon} className="size-4" /> 虚拟节点
          </Button>
          <Button size="sm" variant="outline" onClick={saveDraft} disabled={selectedId == null || busy}>
            <HugeiconsIcon icon={FloppyDiskIcon} className="size-4" /> 保存草稿
          </Button>
          <Button size="sm" onClick={publish} disabled={selectedId == null || busy}>
            <HugeiconsIcon icon={RocketIcon} className="size-4" /> 发布
          </Button>
        </div>
      </div>

      <div className="flex min-h-0 flex-1">
        {/* 左侧任务面板 */}
        <div className="w-56 shrink-0 overflow-y-auto p-3">
          <p className="mb-2 text-xs text-muted-foreground">拖拽任务到画布</p>
          <div className="flex flex-col gap-2">
            {tasks.map((t) => (
              <div
                key={t.id}
                draggable
                onDragStart={(e) => {
                  e.dataTransfer.setData(
                    "application/dw-task",
                    JSON.stringify({ id: t.id, name: t.name }),
                  )
                  e.dataTransfer.effectAllowed = "move"
                }}
                className="flex cursor-grab items-center gap-2 rounded-md border border-border bg-card px-3 py-2 text-xs hover:border-primary"
              >
                <HugeiconsIcon icon={DatabaseIcon} className="size-4 text-primary" />
                <span className="truncate">{t.name}</span>
              </div>
            ))}
            {tasks.length === 0 && (
              <p className="text-xs text-muted-foreground">暂无任务，请先在「任务流」创建</p>
            )}
          </div>
        </div>

        {/* 画布 */}
        <div className="min-w-0 flex-1" ref={wrapperRef} onDrop={onDrop} onDragOver={onDragOver}>
          {selectedId == null ? (
            <div className="flex h-full items-center justify-center text-sm text-muted-foreground">
              选择或新建一个工作流开始编排
            </div>
          ) : (
            <ReactFlow
              nodes={nodes}
              edges={edges}
              nodeTypes={nodeTypes}
              onNodesChange={onNodesChange}
              onEdgesChange={onEdgesChange}
              onConnect={onConnect}
              fitView
              proOptions={{ hideAttribution: true }}
            >
              <Background />
              <Controls />
              <MiniMap pannable zoomable />
            </ReactFlow>
          )}
        </div>
      </div>
    </div>
  )
}

// ─── 导出（包 ReactFlowProvider）──────────────────────────

export function WorkflowCanvasView({ params }: { params?: Record<string, unknown> }) {
  const initialWorkflowId =
    typeof params?.workflowId === "number"
      ? params.workflowId
      : typeof params?.workflowId === "string"
        ? Number(params.workflowId)
        : undefined
  return (
    <ReactFlowProvider>
      <CanvasInner initialWorkflowId={initialWorkflowId} />
    </ReactFlowProvider>
  )
}
