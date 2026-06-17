"use client"

/**
 * 数据开发 IDE（data-development-ide）。
 *
 * 由原「工作流编排」视图升格：左侧常驻类目树 + 右侧内层子 Tab 系统（自管，与顶层 Workspace tab
 * 独立）。子 Tab 两类——画布子 Tab（一个工作流一个）与编辑子 Tab（一个任务一个），按 {kind,id}
 * 去重；点树叶子开/激活对应子 Tab；子 Tab 隐藏式保活（keep-alive）以保编辑态不丢。
 *
 * 画布子 Tab 订阅所属工作流实例的 events/stream，按运行态给节点叠加状态点（不掩盖类型/选中态）。
 * 运行 = 手动触发正式实例（POST /api/workflows/{id}/run），D8：未上线禁用并提示需先发布。
 */
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react"
import { useMotionValue, useTransform, motion } from "motion/react"
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
  CircleIcon,
  DatabaseIcon,
  FloppyDiskIcon,
  RocketIcon,
  Share08Icon,
  Task01Icon,
} from "@hugeicons/core-free-icons"

import { Button } from "@/components/ui/button"
import { Badge } from "@/components/ui/badge"
import { Input } from "@/components/ui/input"
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { TabStrip, type TabStripItem } from "@/components/ui/tab-strip"
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
} from "@/lib/types"
import { ViewStatus } from "./view-status"
import { CatalogTree } from "@/components/workspace/catalog-tree"
import { TaskEditorPane } from "@/components/workspace/task-editor-pane"
import { useEventSource } from "@/lib/workspace/use-event-source"

// ─── 节点数据类型 ──────────────────────────────────────────

interface CanvasNodeData extends Record<string, unknown> {
  nodeType: "TASK" | "VIRTUAL"
  taskId: number | null
  label: string
  /** 运行态（来自事件流叠加，仅显示用，不入 DAG 草稿） */
  runState?: string
}
type CanvasNode = Node<CanvasNodeData>

const shortId = () =>
  (typeof crypto !== "undefined" && crypto.randomUUID
    ? crypto.randomUUID().slice(0, 8)
    : Math.floor(Math.random() * 1e9).toString(36))

/** 节点运行态 → 状态点颜色（语义 token，不掩盖类型/选中态）。 */
function runStateDot(state?: string): string {
  switch (state) {
    case "SUCCESS":
      return "bg-success"
    case "RUNNING":
    case "DISPATCHED":
      return "bg-info animate-pulse"
    case "FAILED":
    case "KILLED":
    case "STOPPED":
      return "bg-destructive"
    case "WAITING":
    case "WAIT_RETRY":
      return "bg-warning animate-pulse"
    default:
      return "bg-muted-foreground/40"
  }
}

// ─── 自定义节点组件 ────────────────────────────────────────

function RunStateDot({ state }: { state?: string }) {
  if (!state) return null
  return (
    <span
      title={state}
      className={`absolute -right-1 -top-1 size-2 rounded-full ring-2 ring-card ${runStateDot(state)}`}
    />
  )
}

function TaskNode({ data, selected }: NodeProps<CanvasNode>) {
  return (
    <div
      className={`relative flex items-center gap-2 rounded-md border bg-card px-3 py-2 text-xs shadow-sm ${
        selected ? "border-primary ring-1 ring-primary" : "border-border"
      }`}
    >
      <Handle type="target" position={Position.Left} />
      <HugeiconsIcon icon={DatabaseIcon} className="size-4 text-primary" />
      <span className="max-w-40 truncate font-medium">{data.label || "任务"}</span>
      <Handle type="source" position={Position.Right} />
      <RunStateDot state={data.runState} />
    </div>
  )
}

function VirtualNode({ data, selected }: NodeProps<CanvasNode>) {
  return (
    <div
      className={`relative flex items-center gap-2 rounded-full border border-dashed bg-muted px-3 py-2 text-xs ${
        selected ? "border-primary ring-1 ring-primary" : "border-muted-foreground/40"
      }`}
    >
      <Handle type="target" position={Position.Left} />
      <HugeiconsIcon icon={CircleIcon} className="size-4 text-muted-foreground" />
      <span className="max-w-40 truncate text-muted-foreground">{data.label || "虚拟节点"}</span>
      <Handle type="source" position={Position.Right} />
      <RunStateDot state={data.runState} />
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

/** /run 闸门返回的 GateResult。 */
interface RunResult {
  outcome: "EXECUTED" | "PENDING_APPROVAL" | "REJECTED"
  resultInstanceId?: string | null
  actionId?: number | null
  message?: string
}

/** 工作流实例详情里的节点（用于把事件里的实例 UUID 桥接到画布 task_def id）。 */
interface InstanceTask {
  id: string
  taskDefId: number
  state: string
}

// ─── 画布子 Tab（须在 ReactFlowProvider 内）──────────────

function CanvasInner({ workflowId, name, onSaved }: { workflowId: number; name: string; onSaved?: () => void }) {
  const [dagVersion, setDagVersion] = useState<number | null>(null)
  const [status, setStatus] = useState<string>("DRAFT")
  const [hasDraft, setHasDraft] = useState(false)
  const [dagNodes, setDagNodes] = useState<CanvasNode[]>([])
  const [edges, setEdges] = useState<Edge[]>([])
  const [dirty, setDirty] = useState(false)
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)

  // 运行态叠加：runWfId 触发订阅；runStateByTaskDef 驱动节点状态点
  const [runWfId, setRunWfId] = useState<string | null>(null)
  const [instanceToTaskDef, setInstanceToTaskDef] = useState<Map<string, number>>(new Map())
  const [runStateByTaskDef, setRunStateByTaskDef] = useState<Map<number, string>>(new Map())

  const { screenToFlowPosition } = useReactFlow()
  const wrapperRef = useRef<HTMLDivElement>(null)
  const nodeTypes = useMemo(() => ({ task: TaskNode, virtual: VirtualNode }), [])

  const online = status === "ONLINE"

  // 加载该工作流的 DAG（草稿态）
  const loadDag = useCallback((id: number) => {
    setBusy(true)
    authFetch(`${API_BASE}/api/workflows/${id}/dag`, { cache: "no-store" })
      .then((r) => r.json() as Promise<ApiResponse<DagView>>)
      .then((j) => {
        if (j.code === 0 && j.data) {
          const { nodes: ns, edges: es } = toFlow(j.data)
          setDagNodes(ns)
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
    setLoading(true)
    loadDag(workflowId)
    setLoading(false)
  }, [workflowId, loadDag])

  // 运行态叠加到展示节点（不入草稿 dagNodes，仅派生）
  const displayNodes = useMemo(
    () =>
      dagNodes.map((n) => ({
        ...n,
        data: { ...n.data, runState: n.data.taskId != null ? runStateByTaskDef.get(n.data.taskId) : undefined },
      })),
    [dagNodes, runStateByTaskDef],
  )

  // 订阅运行实例事件流
  const { events } = useEventSource(
    runWfId ? `${API_BASE}/api/ops/workflow-instances/${runWfId}/events/stream` : "",
  )

  // 状态事件 → 经实例 UUID→task_def id 桥接，更新节点运行态
  useEffect(() => {
    const statusEvents = events.filter((e) => e.type === "status")
    if (statusEvents.length === 0) return
    setRunStateByTaskDef((prev) => {
      const next = new Map(prev)
      for (const ev of statusEvents) {
        try {
          const u = JSON.parse(ev.data) as { taskId?: string; taskState?: string }
          if (u.taskId && u.taskState) {
            const td = instanceToTaskDef.get(u.taskId)
            if (td != null) next.set(td, u.taskState)
          }
        } catch {
          /* ignore malformed event */
        }
      }
      return next
    })
  }, [events, instanceToTaskDef])

  // ── ReactFlow 变更回调（操作草稿源 dagNodes）──
  const onNodesChange = useCallback((changes: NodeChange<CanvasNode>[]) => {
    setDagNodes((nds) => applyNodeChanges(changes, nds))
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
      setDagNodes((nds) => [
        ...nds,
        { id, type: "task", position, data: { nodeType: "TASK", taskId: task.id, label: task.name } },
      ])
      setDirty(true)
    },
    [screenToFlowPosition],
  )

  // ── 工具栏动作 ──
  const addVirtualNode = useCallback(() => {
    const id = `v-${shortId()}`
    setDagNodes((nds) => [
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

  const saveDraft = useCallback(() => {
    setBusy(true)
    const payload = toPayload(dagVersion, dagNodes, edges)
    authFetch(`${API_BASE}/api/workflows/${workflowId}/dag`, {
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
  }, [workflowId, dagVersion, dagNodes, edges])

  const publish = useCallback(() => {
    if (dirty) {
      toast.error("有未保存改动，请先保存草稿")
      return
    }
    setBusy(true)
    authFetch(`${API_BASE}/api/workflows/${workflowId}/publish`, {
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
          onSaved?.()
        } else {
          toast.error(j.message || "发布失败")
        }
      })
      .catch(() => toast.error("发布失败"))
      .finally(() => setBusy(false))
  }, [workflowId, dirty, onSaved])

  // 手动触发正式实例：L1 直执行返回 workflowInstanceId → 订阅事件流给节点变色；
  // D8：未上线禁用。D9：只盯最近一次（新运行重置状态）。
  const runWorkflow = useCallback(async () => {
    setBusy(true)
    try {
      const res = await authFetch(`${API_BASE}/api/workflows/${workflowId}/run`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({}),
      })
      const j = (await res.json()) as ApiResponse<RunResult>
      if (j.code !== 0 || !j.data) {
        toast.error(j.message || "运行失败")
        return
      }
      const r = j.data
      if (r.outcome !== "EXECUTED" || !r.resultInstanceId) {
        if (r.outcome === "PENDING_APPROVAL") {
          toast.info(`需审批：单号 ${r.actionId ?? "?"}`)
        } else {
          toast.error(r.message || "运行未执行")
        }
        return
      }
      const wiId = r.resultInstanceId
      setRunWfId(wiId)
      setRunStateByTaskDef(new Map())
      // 拉实例详情建立 实例UUID→task_def id 映射 + 初始节点状态
      const detail = await authFetch(`${API_BASE}/api/ops/workflow-instances/${wiId}`, { cache: "no-store" })
        .then((d) => d.json() as Promise<ApiResponse<{ tasks: InstanceTask[] }>>)
      const idMap = new Map<string, number>()
      const stateMap = new Map<number, string>()
      for (const t of detail.data?.tasks ?? []) {
        idMap.set(t.id, t.taskDefId)
        stateMap.set(t.taskDefId, t.state)
      }
      setInstanceToTaskDef(idMap)
      setRunStateByTaskDef(stateMap)
      toast.success("已触发运行")
    } catch {
      toast.error("运行失败")
    } finally {
      setBusy(false)
    }
  }, [workflowId])

  if (loading) return <ViewStatus loading />

  return (
    <div className="flex h-full flex-col">
      {/* 工具栏：运行 / 虚拟节点 / 保存 / 发布 + 状态 */}
      <div className="flex flex-wrap items-center gap-2 p-3">
        <HugeiconsIcon icon={Share08Icon} className="text-primary" />
        <h1 className="text-sm font-medium">{name}</h1>
        <Button size="sm" onClick={runWorkflow} disabled={!online || busy}>
          <HugeiconsIcon icon={RocketIcon} className="size-4" /> 运行
        </Button>
        {!online && (
          <span className="text-xs text-muted-foreground">
            未上线不可运行，请先
            <button type="button" className="ml-0.5 underline hover:text-foreground" onClick={publish} disabled={busy}>
              发布
            </button>
          </span>
        )}
        <div className="ml-auto flex items-center gap-2">
          {online && <Badge variant="success">已上线</Badge>}
          {(dirty || hasDraft) && (
            <Badge variant="outline" className="text-amber-600">
              {dirty ? "未保存改动" : "有未发布改动"}
            </Badge>
          )}
          <Button size="sm" variant="outline" onClick={addVirtualNode} disabled={busy}>
            <HugeiconsIcon icon={CircleIcon} className="size-4" /> 虚拟节点
          </Button>
          <Button size="sm" variant="outline" onClick={saveDraft} disabled={busy}>
            <HugeiconsIcon icon={FloppyDiskIcon} className="size-4" /> 保存草稿
          </Button>
          <Button size="sm" onClick={publish} disabled={busy}>
            <HugeiconsIcon icon={RocketIcon} className="size-4" /> 发布
          </Button>
        </div>
      </div>

      <div className="min-h-0 flex-1" ref={wrapperRef} onDrop={onDrop} onDragOver={onDragOver}>
        <ReactFlow
          id={`canvas-${workflowId}`}
          nodes={displayNodes}
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
      </div>
    </div>
  )
}

/** 画布子 Tab（自带 ReactFlowProvider，多子 Tab keep-alive 各自独立）。 */
function WorkflowCanvasPane({
  workflowId,
  name,
  onSaved,
}: {
  workflowId: number
  name: string
  onSaved?: () => void
}) {
  return (
    <ReactFlowProvider>
      <CanvasInner workflowId={workflowId} name={name} onSaved={onSaved} />
    </ReactFlowProvider>
  )
}

// ─── 数据开发 IDE 壳 ────────────────────────────────────────

/** 左侧类目树面板宽度：默认 / 可拖拽范围 / 持久化键 */
const CATALOG_DEFAULT_WIDTH = 256 // = w-64
const CATALOG_MIN_WIDTH = 180
const CATALOG_MAX_WIDTH = 480
const CATALOG_WIDTH_KEY = "dw.dataDev.catalogWidth"

type SubTabKind = "canvas" | "editor"
interface SubTab {
  kind: SubTabKind
  id: number
  name: string
}
const tabKey = (t: SubTab) => `${t.kind}:${t.id}`

function DataDevIdeShell({ initialWorkflowId }: { initialWorkflowId?: number }) {
  const [tabs, setTabs] = useState<SubTab[]>([])
  const [activeKey, setActiveKey] = useState<string | null>(null)
  // closeTab 用 ref 读最新 activeKey，避免 useCallback 闭包陈旧
  const activeKeyRef = useRef<string | null>(null)
  activeKeyRef.current = activeKey
  const [treeReloadKey, setTreeReloadKey] = useState(0)
  const bumpTree = useCallback(() => setTreeReloadKey((k) => k + 1), [])

  // ── 类目树面板宽度（右缘分割线拖拽，localStorage 持久化）────────
  // 与 AgentRail 同模式：motion value 驱动渲染，拖拽过程零 React 提交
  const [, setCatalogWidth] = useState(CATALOG_DEFAULT_WIDTH)
  const catalogWidthMotion = useMotionValue(CATALOG_DEFAULT_WIDTH)
  const [catalogHydrated, setCatalogHydrated] = useState(false)
  useLayoutEffect(() => {
    const saved = Number(localStorage.getItem(CATALOG_WIDTH_KEY))
    if (saved >= CATALOG_MIN_WIDTH && saved <= CATALOG_MAX_WIDTH) {
      setCatalogWidth(saved)
      catalogWidthMotion.set(saved)
    }
    setCatalogHydrated(true)
  }, [catalogWidthMotion])
  const catalogWidthStyle = useTransform(catalogWidthMotion, (v) => `${Math.round(v)}px`)
  const catalogWidthProp = catalogHydrated
    ? catalogWidthStyle
    : "var(--dw-catalog-width, 256px)"

  const onCatalogResizeDown = useCallback(
    (e: React.PointerEvent) => {
      e.preventDefault()
      const startX = e.clientX
      const startW = catalogWidthMotion.get()
      let current = startW
      const onMove = (ev: PointerEvent) => {
        current = Math.min(
          CATALOG_MAX_WIDTH,
          Math.max(CATALOG_MIN_WIDTH, startW + (ev.clientX - startX)),
        )
        catalogWidthMotion.set(current)
      }
      const onUp = () => {
        window.removeEventListener("pointermove", onMove)
        window.removeEventListener("pointerup", onUp)
        document.body.style.cursor = ""
        document.body.style.userSelect = ""
        setCatalogWidth(current)
        localStorage.setItem(CATALOG_WIDTH_KEY, String(current))
      }
      document.body.style.cursor = "col-resize"
      document.body.style.userSelect = "none"
      window.addEventListener("pointermove", onMove)
      window.addEventListener("pointerup", onUp)
    },
    [catalogWidthMotion],
  )

  // 新建任务 Dialog
  const [taskDialog, setTaskDialog] = useState(false)
  const [taskName, setTaskName] = useState("")
  const [taskType, setTaskType] = useState<"SQL" | "SHELL">("SQL")
  const [creating, setCreating] = useState(false)

  // 新建工作流 Dialog
  const [wfDialog, setWfDialog] = useState(false)
  const [wfName, setWfName] = useState("")

  const openTab = useCallback((t: SubTab) => {
    setTabs((prev) => (prev.some((x) => x.kind === t.kind && x.id === t.id) ? prev : [...prev, t]))
    setActiveKey(tabKey(t))
  }, [])

  const closeTab = useCallback((key: string) => {
    setTabs((prev) => {
      const idx = prev.findIndex((t) => tabKey(t) === key)
      const next = prev.filter((t) => tabKey(t) !== key)
      if (key === activeKeyRef.current) {
        const fallback = next[idx] ?? next[idx - 1] ?? null
        setActiveKey(fallback ? tabKey(fallback) : null)
      }
      return next
    })
  }, [])

  // 标准批量关闭 —— 与驾驶舱 Tab 行为一致（TabStrip 条件渲染：传了才显示菜单项）
  const closeOthers = useCallback((key: string) => {
    setTabs((prev) => prev.filter((t) => tabKey(t) === key))
    setActiveKey(key)
  }, [])

  const closeRight = useCallback((key: string) => {
    setTabs((prev) => {
      const idx = prev.findIndex((t) => tabKey(t) === key)
      if (idx < 0) return prev
      const next = prev.slice(0, idx + 1)
      if (!next.some((t) => tabKey(t) === activeKeyRef.current)) setActiveKey(key)
      return next
    })
  }, [])

  const closeLeft = useCallback((key: string) => {
    setTabs((prev) => {
      const idx = prev.findIndex((t) => tabKey(t) === key)
      if (idx < 0) return prev
      const next = prev.slice(idx)
      if (!next.some((t) => tabKey(t) === activeKeyRef.current)) setActiveKey(key)
      return next
    })
  }, [])

  const closeAll = useCallback(() => {
    setTabs([])
    setActiveKey(null)
  }, [])

  // 深链：初始工作流自动开画布子 Tab
  useEffect(() => {
    if (initialWorkflowId != null) openTab({ kind: "canvas", id: initialWorkflowId, name: "工作流" })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // 新建任务：POST 草稿 → 直接开编辑子 Tab 进编辑态（D2/D5.3）
  const submitCreateTask = async () => {
    if (!taskName.trim()) return
    setCreating(true)
    try {
      const res = await authFetch(`${API_BASE}/api/tasks`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ name: taskName.trim(), type: taskType, content: "" }),
      })
      const j = (await res.json()) as ApiResponse<TaskDef>
      if (j.code === 0 && j.data) {
        openTab({ kind: "editor", id: j.data.id, name: j.data.name })
        setTaskDialog(false)
        setTaskName("")
        bumpTree()
        toast.success("已创建任务草稿")
      } else {
        toast.error(j.message || "创建失败")
      }
    } catch {
      toast.error("创建失败")
    } finally {
      setCreating(false)
    }
  }

  const submitCreateWorkflow = async () => {
    if (!wfName.trim()) return
    setCreating(true)
    try {
      const res = await authFetch(`${API_BASE}/api/workflows`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ name: wfName.trim() }),
      })
      const j = (await res.json()) as ApiResponse<WorkflowDef>
      if (j.code === 0 && j.data) {
        openTab({ kind: "canvas", id: j.data.id, name: j.data.name })
        setWfDialog(false)
        setWfName("")
        bumpTree()
        toast.success("已创建工作流草稿")
      } else {
        toast.error(j.message || "创建失败")
      }
    } catch {
      toast.error("创建失败")
    } finally {
      setCreating(false)
    }
  }

  const stripTabs: TabStripItem[] = tabs.map((t) => ({
    id: tabKey(t),
    label: t.name,
    icon: t.kind === "canvas" ? Share08Icon : Task01Icon,
  }))

  return (
    <div className="flex h-full gap-3 p-3">
      {/* 左侧常驻类目树 —— 外层 relative + pr-1.5，handle 落在 card 右侧 gap 中（同 AgentRail 结构） */}
      <div className="relative flex shrink-0 flex-col pr-1.5">
        <motion.div
          className="flex h-full flex-col overflow-hidden rounded-[var(--radius-lg)] border bg-card shadow-lg"
          style={{ width: catalogWidthProp }}
        >
          <div className="min-h-0 flex-1 overflow-y-auto p-3">
            <CatalogTree
              draggableTasksToCanvas
              enableMove
              showTagFilter
              reloadKey={treeReloadKey}
              onOpenTask={(id, name) => openTab({ kind: "editor", id, name })}
              onOpenWorkflow={(id, name) => openTab({ kind: "canvas", id, name })}
            />
          </div>
        </motion.div>
        {/* 右缘分割线拖拽 */}
        <div
          onPointerDown={onCatalogResizeDown}
          role="separator"
          aria-orientation="vertical"
          aria-label="拖拽调整类目面板宽度"
          className="group/resize absolute inset-y-3 right-0 z-20 flex w-2 cursor-col-resize touch-none items-center justify-center"
        >
          <div className="h-12 w-0.5 rounded-full bg-border/0 transition-colors group-hover/resize:bg-border" />
        </div>
      </div>

      {/* 右侧内层子 Tab 区 —— Card 边框，对齐 cockpit StatCard 观感 */}
      <div className="flex min-w-0 flex-1 flex-col overflow-hidden rounded-[var(--radius-lg)] border bg-card shadow-lg">
        {tabs.length > 0 && (
          <TabStrip
            size="sm"
            surface="background"
            tabs={stripTabs}
            activeId={activeKey}
            onActivate={setActiveKey}
            onClose={closeTab}
            onCloseOthers={closeOthers}
            onCloseRight={closeRight}
            onCloseLeft={closeLeft}
            onCloseAll={closeAll}
            trailing={null}
          />
        )}

        {/* 子 Tab 内容（隐藏式保活 keep-alive） */}
        <div className="relative min-h-0 flex-1">
          {tabs.length === 0 ? (
            <div className="flex h-full items-center justify-center text-sm text-muted-foreground">
              从左侧类目树点开任务/工作流
            </div>
          ) : (
            tabs.map((t) => (
              <div
                key={tabKey(t)}
                className={tabKey(t) === activeKey ? "absolute inset-0" : "absolute inset-0 hidden"}
              >
                {t.kind === "canvas" ? (
                  <WorkflowCanvasPane workflowId={t.id} name={t.name} onSaved={bumpTree} />
                ) : (
                  <TaskEditorPane taskId={t.id} onSaved={bumpTree} />
                )}
              </div>
            ))
          )}
        </div>
      </div>

      {/* 新建任务 Dialog（名 + 类型） */}
      <Dialog open={taskDialog} onOpenChange={setTaskDialog}>
        <DialogContent className="max-w-sm">
          <DialogHeader>
            <DialogTitle>新建任务</DialogTitle>
          </DialogHeader>
          <Input
            autoFocus
            value={taskName}
            placeholder="任务名称"
            onChange={(e) => setTaskName(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter") {
                e.preventDefault()
                submitCreateTask()
              }
            }}
          />
          <div className="flex items-center gap-2">
            <span className="text-xs text-muted-foreground">类型</span>
            <Button
              size="xs"
              variant={taskType === "SQL" ? "default" : "outline"}
              onClick={() => setTaskType("SQL")}
            >
              SQL
            </Button>
            <Button
              size="xs"
              variant={taskType === "SHELL" ? "default" : "outline"}
              onClick={() => setTaskType("SHELL")}
            >
              SHELL
            </Button>
          </div>
          <DialogFooter>
            <DialogClose render={<Button variant="ghost" />}>取消</DialogClose>
            <Button onClick={submitCreateTask} disabled={creating}>
              创建
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* 新建工作流 Dialog */}
      <Dialog open={wfDialog} onOpenChange={setWfDialog}>
        <DialogContent className="max-w-sm">
          <DialogHeader>
            <DialogTitle>新建工作流</DialogTitle>
          </DialogHeader>
          <Input
            autoFocus
            value={wfName}
            placeholder="工作流名称"
            onChange={(e) => setWfName(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter") {
                e.preventDefault()
                submitCreateWorkflow()
              }
            }}
          />
          <DialogFooter>
            <DialogClose render={<Button variant="ghost" />}>取消</DialogClose>
            <Button onClick={submitCreateWorkflow} disabled={creating}>
              创建
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}

// ─── 导出（视图注册表入口）──────────────────────────────────

export function WorkflowCanvasView({ params }: { params?: Record<string, unknown> }) {
  const initialWorkflowId =
    typeof params?.workflowId === "number"
      ? params.workflowId
      : typeof params?.workflowId === "string"
        ? Number(params.workflowId)
        : undefined
  return <DataDevIdeShell initialWorkflowId={initialWorkflowId} />
}
