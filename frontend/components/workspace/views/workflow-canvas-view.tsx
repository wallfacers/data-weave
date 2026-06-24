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
import { createContext, useCallback, useContext, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react"
import { useLocale, useTranslations } from "next-intl"
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
  PlayIcon,
  RocketIcon,
  Share08Icon,
  StopIcon,
  Task01Icon,
} from "@hugeicons/core-free-icons"

import { Button } from "@/components/ui/button"
import { Badge } from "@/components/ui/badge"
import { Input } from "@/components/ui/input"
import { DropdownSelect } from "@/components/ui/select"
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { TabStrip, type TabStripItem } from "@/components/ui/tab-strip"
import {
  ContextMenu,
  ContextMenuTrigger,
  ContextMenuContent,
  ContextMenuItem,
  ContextMenuSeparator,
} from "@/components/ui/context-menu"
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
  type WorkflowDefVersion,
  type WorkflowDetail,
  type DriftResult,
  type LatestInstanceView,
} from "@/lib/types"
import { isTerminalInstanceState } from "@/lib/workspace/run-dot-state"
import { yesterdayBizDate } from "@/lib/workspace/biz-date"
import { wouldCreateCycle } from "@/lib/workspace/dag-helpers"
import { ViewStatus } from "./view-status"
import { CatalogTree } from "@/components/workspace/catalog-tree"
import { TaskEditorPane } from "@/components/workspace/task-editor-pane"
import { WorkflowConfigPanel } from "@/components/workspace/workflow-config-panel"
import { VersionHistoryPanel, type VersionInfo } from "@/components/workspace/version-history-panel"
import { RollbackConfirmDialog } from "@/components/workspace/rollback-confirm-dialog"
import { WorkflowVersionDetailDialog } from "@/components/workspace/workflow-version-detail-dialog"
import { VersionDiffDialog, type VersionDiffInput } from "@/components/workspace/version-diff-dialog"
import { DwScroll } from "@/components/ui/dw-scroll"
import { cn } from "@/lib/utils"
import { RunLogsTabs, useRunLogTabs, type RunTab } from "@/components/workspace/run-logs-tabs"
import { useEventSource } from "@/lib/workspace/use-event-source"
import { useCatalogTreeStore } from "@/lib/workspace/catalog-tree-store"
import { useWorkspaceStore } from "@/lib/workspace/store"

// ─── 节点数据类型 ──────────────────────────────────────────

interface CanvasNodeData extends Record<string, unknown> {
  nodeType: "TASK" | "VIRTUAL"
  taskId: number | null
  label: string
  /** 运行态（来自事件流叠加，仅显示用，不入 DAG 草稿） */
  runState?: string
}
type CanvasNode = Node<CanvasNodeData>

/**
 * 节点右键菜单动作（由 CanvasInner 注入，节点组件经 context 消费）。
 * 用 context 而非把回调塞进 node.data，避免污染草稿 payload 与触发多余重渲染。
 */
interface NodeActions {
  /** 查看该 TASK 节点对应实例日志（需已有运行实例，否则 canViewLog=false）。 */
  onViewLog: (taskDefId: number, label: string) => void
  /** 单独运行该 TASK 节点（脱离工作流，复用 /api/tasks/{id}/run）。 */
  onRunNode: (taskId: number, label: string) => void
  /** 运行到该节点（TO_NODE：本节点 + 其全部前驱闭包）。 */
  onRunToNode: (nodeKey: string) => void
  /** 运行该节点的全部下游（DOWNSTREAM 后继闭包）。 */
  onRunDownstream: (nodeKey: string) => void
  /** 工作流是否已上线（未上线时禁用子图运行菜单）。 */
  online: boolean
  /** 删除节点（及其关联边）。 */
  onDeleteNode: (id: string) => void
  /** 该 TASK 节点是否已有可看日志的运行实例。 */
  canViewLog: (taskDefId: number) => boolean
}
const NodeActionsContext = createContext<NodeActions | null>(null)

// 画布运行日志区高度持久化键（高度逻辑在 useRunLogTabs 内）
const CANVAS_LOG_HEIGHT_KEY = "dw.workflowCanvas.logHeight"

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

function TaskNode({ id, data, selected }: NodeProps<CanvasNode>) {
  const t = useTranslations("workflowCanvas")
  const actions = useContext(NodeActionsContext)
  const label = data.label || t("nodeTaskFallback")
  const taskId = data.taskId
  const canLog = taskId != null && (actions?.canViewLog(taskId) ?? false)
  return (
    <ContextMenu>
      <ContextMenuTrigger
        className={`relative flex items-center gap-2 rounded-md border bg-card px-3 py-2 text-xs shadow-sm ${
          selected ? "border-primary ring-1 ring-primary" : "border-border"
        }`}
      >
        <Handle type="target" position={Position.Left} />
        <HugeiconsIcon icon={DatabaseIcon} className="size-4 text-primary" />
        <span className="max-w-40 truncate font-medium">{label}</span>
        <Handle type="source" position={Position.Right} />
        <RunStateDot state={data.runState} />
      </ContextMenuTrigger>
      <ContextMenuContent>
        <ContextMenuItem
          disabled={!canLog}
          onClick={() => taskId != null && actions?.onViewLog(taskId, label)}
        >
          {t("nodeMenuViewLog")}
        </ContextMenuItem>
        <ContextMenuItem
          disabled={taskId == null}
          onClick={() => taskId != null && actions?.onRunNode(taskId, label)}
        >
          {t("nodeMenuRunNode")}
        </ContextMenuItem>
        <ContextMenuItem disabled={!actions?.online} onClick={() => actions?.onRunToNode(id)}>
          {t("nodeMenuRunToNode")}
        </ContextMenuItem>
        <ContextMenuItem disabled={!actions?.online} onClick={() => actions?.onRunDownstream(id)}>
          {t("nodeMenuRunDownstream")}
        </ContextMenuItem>
        <ContextMenuSeparator />
        <ContextMenuItem variant="destructive" onClick={() => actions?.onDeleteNode(id)}>
          {t("nodeMenuDelete")}
        </ContextMenuItem>
      </ContextMenuContent>
    </ContextMenu>
  )
}

function VirtualNode({ id, data, selected }: NodeProps<CanvasNode>) {
  const t = useTranslations("workflowCanvas")
  const actions = useContext(NodeActionsContext)
  return (
    <ContextMenu>
      <ContextMenuTrigger
        className={`relative flex items-center gap-2 rounded-full border border-dashed bg-muted px-3 py-2 text-xs ${
          selected ? "border-primary ring-1 ring-primary" : "border-muted-foreground/40"
        }`}
      >
        <Handle type="target" position={Position.Left} />
        <HugeiconsIcon icon={CircleIcon} className="size-4 text-muted-foreground" />
        <span className="max-w-40 truncate text-muted-foreground">{data.label || t("nodeVirtualFallback")}</span>
        <Handle type="source" position={Position.Right} />
        <RunStateDot state={data.runState} />
      </ContextMenuTrigger>
      <ContextMenuContent>
        {/* VIRTUAL 节点无任务：仅保留删除 */}
        <ContextMenuItem variant="destructive" onClick={() => actions?.onDeleteNode(id)}>
          {t("nodeMenuDelete")}
        </ContextMenuItem>
      </ContextMenuContent>
    </ContextMenu>
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
  // 弱依赖：虚线 + 流动动画做视觉区分（不阻塞语义由后端就绪门按 strength 处理）
  const edges: Edge[] = dag.edges.map((e) => {
    const strength = e.strength ?? "STRONG"
    return {
      id: `${e.fromNodeKey}->${e.toNodeKey}`,
      source: e.fromNodeKey,
      target: e.toNodeKey,
      data: { strength },
      ...(strength === "WEAK" ? { animated: true, style: { strokeDasharray: "6 4" } } : {}),
    }
  })
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
  const dagEdges: DagEdge[] = edges.map((e) => ({
    fromNodeKey: e.source,
    toNodeKey: e.target,
    strength: (e.data?.strength as "STRONG" | "WEAK" | undefined) ?? "STRONG",
  }))
  return { version, nodes: dagNodes, edges: dagEdges }
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

function CanvasInner({ workflowId, name }: { workflowId: number; name: string }) {
  const t = useTranslations("workflowCanvas")
  const locale = useLocale()
  const [dagVersion, setDagVersion] = useState<number | null>(null)
  const [status, setStatus] = useState<string>("DRAFT")
  const [hasDraft, setHasDraft] = useState(false)
  // 漂移：已发布快照钉死的任务版本落后于任务最新发布版（或 DAG 草稿）；读侧计算，不落库。
  const [drift, setDrift] = useState<DriftResult | null>(null)

  // 右侧栏
  const [sidebarTab, setSidebarTab] = useState<"config" | "versions">("config")
  const [wfName, setWfName] = useState(name)
  const [wfDesc, setWfDesc] = useState("")
  const [wfScheduleType, setWfScheduleType] = useState("MANUAL")
  const [wfCron, setWfCron] = useState("")
  const [wfPriority, setWfPriority] = useState(5)
  const [wfVersions, setWfVersions] = useState<WorkflowDefVersion[]>([])
  const [rollbackTarget, setRollbackTarget] = useState<WorkflowDefVersion | null>(null)
  const [viewingVersion, setViewingVersion] = useState<WorkflowDefVersion | null>(null)
  const [diffVersions, setDiffVersions] = useState<[WorkflowDefVersion, WorkflowDefVersion] | null>(null)
  const [rolling, setRolling] = useState(false)
  const [dagNodes, setDagNodes] = useState<CanvasNode[]>([])
  const [edges, setEdges] = useState<Edge[]>([])
  const [dirty, setDirty] = useState(false)
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)

  // 运行态叠加：runWfId 触发订阅；runStateByTaskDef 驱动节点状态点；runStatus 驱动工作流整体运行徽标
  const [runWfId, setRunWfId] = useState<string | null>(null)
  const [runStatus, setRunStatus] = useState<string | null>(null)
  const [stopping, setStopping] = useState(false)
  const [instanceToTaskDef, setInstanceToTaskDef] = useState<Map<string, number>>(new Map())
  const [runStateByTaskDef, setRunStateByTaskDef] = useState<Map<number, string>>(new Map())
  // 节点取日志用：taskDefId → 实例 UUID（与着色同源，按 taskDefId 键）
  const [taskDefToInstance, setTaskDefToInstance] = useState<Map<number, string>>(new Map())

  // 运行日志 Tabs（每节点一条实例日志流，复用任务编辑器同一套零件）
  const {
    runTabs,
    activeRunTab,
    setActiveRunTab,
    logHeight,
    onLogResizeDown,
    openRunTab,
    closeRunTab,
    closeOtherRunTabs,
    closeRunTabsRight,
    closeRunTabsLeft,
    closeAllRunTabs,
  } = useRunLogTabs(CANVAS_LOG_HEIGHT_KEY)
  // 自动顶日志去重：已自动开过的实例不再抢焦（用户切看其它节点不被打断）
  const autoOpenedRef = useRef<Set<string>>(new Set())
  // 边右键菜单（边是 SVG path，不便套 ContextMenu 包裹，用轻量浮层）
  const [edgeMenu, setEdgeMenu] = useState<{ x: number; y: number; id: string } | null>(null)
  // 运行范围 Dialog（工具栏「运行」入口）：scope + 目标节点
  const [runDialogOpen, setRunDialogOpen] = useState(false)
  const [runScope, setRunScope] = useState<"FULL" | "TO_NODE" | "DOWNSTREAM" | "ONLY_NODE">("FULL")
  const [runTargetKey, setRunTargetKey] = useState("")

  const { screenToFlowPosition, deleteElements } = useReactFlow()
  const wrapperRef = useRef<HTMLDivElement>(null)
  const nodeTypes = useMemo(() => ({ task: TaskNode, virtual: VirtualNode }), [])

  const online = status === "ONLINE"

  // 漂移明细 tooltip：逐节点 pinned→latest，含 DAG 草稿提示
  const driftTitle = useMemo(() => {
    if (!drift?.drifted) return ""
    const lines = drift.driftedNodes.map((n) => `${n.nodeKey}: v${n.pinned} → v${n.latest}`)
    if (drift.dagDraft) lines.push(t("driftDagHint"))
    return lines.join("\n")
  }, [drift, t])

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
          toast.error(j.message)
        }
      })
      .catch(() => toast.error(t("toastLoadDagFailed")))
      .finally(() => setBusy(false))
  }, [t])

  // 加载工作流详情（含版本历史 + 配置字段）
  const loadWfDetail = useCallback(() => {
    authFetch(`${API_BASE}/api/workflows/${workflowId}`)
      .then((r) => r.json() as Promise<ApiResponse<WorkflowDetail>>)
      .then((j) => {
        if (j.code === 0 && j.data) {
          const wf = j.data.workflow
          setWfName(wf.name ?? "")
          setWfDesc(wf.description ?? "")
          setWfScheduleType(wf.scheduleType ?? "MANUAL")
          setWfCron(wf.cron ?? "")
          setWfPriority(wf.priority ?? 5)
          setWfVersions(j.data.versions ?? [])
        }
      })
      .catch(() => { /* 静默失败，不影响 DAG 加载 */ })
  }, [workflowId])

  // 加载漂移状态（读侧计算，未发布工作流后端返回 drifted=false）
  const loadDrift = useCallback(() => {
    authFetch(`${API_BASE}/api/workflows/${workflowId}/drift`, { cache: "no-store" })
      .then((r) => r.json() as Promise<ApiResponse<DriftResult>>)
      .then((j) => { if (j.code === 0 && j.data) setDrift(j.data) })
      .catch(() => { /* 静默失败，不影响画布 */ })
  }, [workflowId])

  useEffect(() => {
    setLoading(true)
    loadDrift()
    loadDag(workflowId)
    loadWfDetail()
    setLoading(false)
  }, [workflowId, loadDag, loadWfDetail])

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

  // 状态事件 → 经实例 UUID→task_def id 桥接，更新节点运行态；workflowState 更新整体运行徽标
  useEffect(() => {
    const statusEvents = events.filter((e) => e.type === "status")
    if (statusEvents.length === 0) return
    setRunStateByTaskDef((prev) => {
      const next = new Map(prev)
      for (const ev of statusEvents) {
        try {
          const u = JSON.parse(ev.data) as { taskId?: string; taskState?: string; workflowState?: string }
          if (u.taskId && u.taskState) {
            const td = instanceToTaskDef.get(u.taskId)
            if (td != null) next.set(td, u.taskState)
          }
          if (u.workflowState) setRunStatus(u.workflowState)
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
          toast.error(t("toastCycleRejected"))
          return eds
        }
        setDirty(true)
        return addEdge(conn, eds)
      })
    },
    [t],
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
        data: { nodeType: "VIRTUAL", taskId: null, label: t("nodeVirtualFallback") },
      },
    ])
    setDirty(true)
  }, [t])

  const saveDraft = useCallback(() => {
    setBusy(true)
    const payload = toPayload(dagVersion, dagNodes, edges)
    // 并行保存 DAG + 配置
    Promise.all([
      authFetch(`${API_BASE}/api/workflows/${workflowId}/dag`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      }).then((r) => r.json() as Promise<ApiResponse<DagView>>),
      authFetch(`${API_BASE}/api/workflows/${workflowId}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          name: wfName,
          description: wfDesc || null,
          scheduleType: wfScheduleType,
          cron: wfScheduleType === "CRON" ? wfCron : null,
          priority: wfPriority,
        }),
      }).then((r) => r.json() as Promise<ApiResponse<WorkflowDef>>),
    ])
      .then(([dagJ, _]) => {
        if (dagJ.code === 0 && dagJ.data) {
          setDagVersion(dagJ.data.version)
          setHasDraft(dagJ.data.hasDraftChange === 1)
          setDirty(false)
          toast.success(t("toastDraftSaved"))
        } else if (dagJ.code === 409) {
          toast.error(t("toastConflict"))
        } else {
          toast.error(dagJ.message)
        }
      })
      .catch(() => toast.error(t("toastSaveFailed")))
      .finally(() => setBusy(false))
  }, [workflowId, dagVersion, dagNodes, edges, wfName, wfDesc, wfScheduleType, wfCron, wfPriority, t])

  const publish = useCallback(() => {
    if (dirty) {
      toast.error(t("toastUnsavedBeforePublish"))
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
          loadDrift() // 重新晋级/发布后刷新漂移（应清空）
          toast.success(t("toastPublished", { version: j.data.currentVersionNo }))
          useCatalogTreeStore.getState().updateWorkflow(workflowId, {
            status: j.data.status,
            currentVersionNo: j.data.currentVersionNo,
            hasDraftChange: 0,
          })
        } else {
          toast.error(j.message)
        }
      })
      .catch(() => toast.error(t("toastPublishFailed")))
      .finally(() => setBusy(false))
  }, [workflowId, dirty, t, loadDrift])

  // 接管一个运行实例（首跑与重开续接共用）：订阅事件流、拉详情建「实例UUID↔task_def id」映射、
  // 初始节点态着色、对运行中节点顶日志。initialStatus 为整体运行徽标初值(首跑 RUNNING / 续接=后端实际态)。
  const attachRunningInstance = useCallback(async (wiId: string, initialStatus: string) => {
    setRunWfId(wiId)
    setRunStatus(initialStatus)
    setRunStateByTaskDef(new Map())
    autoOpenedRef.current = new Set() // 重置自动顶日志去重
    const detail = await authFetch(`${API_BASE}/api/ops/workflow-instances/${wiId}`, { cache: "no-store" })
      .then((d) => d.json() as Promise<ApiResponse<{ tasks: InstanceTask[] }>>)
    const idMap = new Map<string, number>()
    const defMap = new Map<number, string>()
    const stateMap = new Map<number, string>()
    for (const it of detail.data?.tasks ?? []) {
      idMap.set(it.id, it.taskDefId)
      defMap.set(it.taskDefId, it.id)
      stateMap.set(it.taskDefId, it.state)
      // 已在运行的节点：直接顶日志（覆盖"SSE 连上前就进 RUNNING"的竞态 / 续接时已在跑）
      if ((it.state === "RUNNING" || it.state === "DISPATCHED") && !autoOpenedRef.current.has(it.id)) {
        autoOpenedRef.current.add(it.id)
        const nm = dagNodes.find((n) => n.data.taskId === it.taskDefId)?.data.label || name
        openRunTab({ instanceId: it.id, taskName: nm, startedAt: new Date().toISOString(), kind: "log" })
      }
    }
    setInstanceToTaskDef(idMap)
    setTaskDefToInstance(defMap)
    setRunStateByTaskDef(stateMap)
  }, [dagNodes, name, openRunTab])

  // 手动触发正式实例（带运行范围）：L1 直执行返回 workflowInstanceId → 订阅事件流给节点变色；
  // D8：未上线禁用（后端 409，前端 toast）。D9：只盯最近一次（新运行重置状态）。
  // scope: FULL=全图 / TO_NODE=运行到 target（含其全部前驱）/ DOWNSTREAM=运行 target 的全部下游。
  const runWorkflowWithScope = useCallback(
    async (scope: "FULL" | "TO_NODE" | "DOWNSTREAM", targetNodeKey?: string) => {
      setBusy(true)
      try {
        const res = await authFetch(`${API_BASE}/api/workflows/${workflowId}/run`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          // 显式带业务日期（T-1）：脚本里的 $bizdate/$bizmonth 等平台占位符方有值，否则节点解析即 FAILED 且无日志。
          body: JSON.stringify({ bizDate: yesterdayBizDate(), scope, targetNodeKey: targetNodeKey ?? null }),
        })
        const j = (await res.json()) as ApiResponse<RunResult>
        if (j.code !== 0 || !j.data) {
          toast.error(j.message)
          return
        }
        const r = j.data
        if (r.outcome !== "EXECUTED" || !r.resultInstanceId) {
          if (r.outcome === "PENDING_APPROVAL") {
            toast.info(t("toastPendingApproval", { actionId: r.actionId ?? "?" }))
          } else {
            toast.error(r.message || j.message)
          }
          return
        }
        await attachRunningInstance(r.resultInstanceId, "RUNNING")
        toast.success(t("toastRunTriggered"))
      } catch {
        toast.error(t("toastRunFailed"))
      } finally {
        setBusy(false)
      }
    },
    [workflowId, t, attachRunningInstance],
  )

  // 运行范围 Dialog 目标节点选项：ONLY_NODE 仅列 TASK 节点（单跑须有任务），其余列全部节点
  const runTargetOptions = useMemo(() => {
    const nodes = runScope === "ONLY_NODE" ? dagNodes.filter((n) => n.data.taskId != null) : dagNodes
    return nodes.map((n) => ({ value: n.id, label: n.data.label || n.id }))
  }, [dagNodes, runScope])

  // 单跑一个 TASK 节点（脱离工作流，POST /api/tasks/{id}/run）：节点右键「单独运行」与运行范围 Dialog 的 ONLY_NODE 共用。
  const runSingleTaskNode = useCallback(async (taskId: number, label: string) => {
    try {
      const res = await authFetch(`${API_BASE}/api/tasks/${taskId}/run`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        // 同工作流运行：带 T-1 业务日期，保证单跑该节点时 $bizdate 等占位符可解析。
        body: JSON.stringify({ bizDate: yesterdayBizDate() }),
      })
      const j = (await res.json()) as ApiResponse<RunResult>
      if (j.code !== 0 || !j.data) {
        toast.error(j.message || t("toastNodeRunFailed"))
        return
      }
      const r = j.data
      if (r.outcome === "EXECUTED" && r.resultInstanceId) {
        openRunTab({ instanceId: r.resultInstanceId, taskName: label, startedAt: new Date().toISOString(), kind: "log" })
        toast.success(t("toastNodeRunTriggered"))
      } else if (r.outcome === "PENDING_APPROVAL") {
        toast.info(t("toastPendingApproval", { actionId: r.actionId ?? "?" }))
      } else {
        toast.error(r.message || t("toastNodeRunNotExecuted"))
      }
    } catch {
      toast.error(t("toastNodeRunFailed"))
    }
  }, [openRunTab, t])

  // 运行范围 Dialog 提交：FULL→全图；TO_NODE/DOWNSTREAM→带 target 子图；ONLY_NODE→单跑该 TASK
  const confirmRunDialog = useCallback(() => {
    const scope = runScope
    setRunDialogOpen(false)
    if (scope === "ONLY_NODE") {
      const node = dagNodes.find((n) => n.id === runTargetKey && n.data.taskId != null)
      if (node) runSingleTaskNode(node.data.taskId as number, node.data.label || node.id)
      return
    }
    if (scope === "FULL") {
      runWorkflowWithScope("FULL")
      return
    }
    runWorkflowWithScope(scope, runTargetKey || undefined)
  }, [runScope, runTargetKey, dagNodes, runSingleTaskNode, runWorkflowWithScope])

  // 重开/刷新续接（run-state-resume）：查后端最近工作流实例,非终态则接管 → 续订阅事件流 + 节点重着色 + 停止按钮恢复。
  useEffect(() => {
    let cancelled = false
    ;(async () => {
      try {
        const res = await authFetch(`${API_BASE}/api/ops/workflows/${workflowId}/latest-instance`)
        const j = (await res.json()) as ApiResponse<LatestInstanceView | null>
        if (cancelled || j.code !== 0 || !j.data) return
        if (!isTerminalInstanceState(j.data.state)) {
          await attachRunningInstance(j.data.id, j.data.state)
        }
      } catch {
        /* 续接失败静默,不影响编辑 */
      }
    })()
    return () => {
      cancelled = true
    }
  }, [workflowId, attachRunningInstance])

  // 停止当前工作流运行实例：→ STOPPED（后端各运行中节点置 STOPPED 发事件→节点实时变红 + 插手动停止日志）。
  const stopWorkflow = useCallback(async () => {
    if (!runWfId) return
    setStopping(true)
    try {
      const res = await authFetch(`${API_BASE}/api/ops/instances/${runWfId}/kill`, { method: "POST" })
      const j = (await res.json()) as ApiResponse<unknown>
      if (j.code === 0) {
        setRunStatus("STOPPED")
        toast.success(t("toastStopped"))
      } else {
        toast.error(j.message || t("toastStopFailed"))
      }
    } catch {
      toast.error(t("toastStopFailed"))
    } finally {
      setStopping(false)
    }
  }, [runWfId, t])

  // 切换边依赖强度（边右键菜单）：更新 edge.data.strength + 弱依赖虚线视觉，置脏待保存。
  const setEdgeStrength = useCallback((edgeId: string, strength: "STRONG" | "WEAK") => {
    setEdges((eds) =>
      eds.map((e) =>
        e.id === edgeId
          ? {
              ...e,
              data: { ...e.data, strength },
              animated: strength === "WEAK",
              style: strength === "WEAK" ? { strokeDasharray: "6 4" } : undefined,
            }
          : e,
      ),
    )
    setDirty(true)
    setEdgeMenu(null)
  }, [])

  // 工作流整体运行徽标：运行中/成功/失败/已停止
  const runStatusBadge = useMemo(() => {
    if (!runStatus) return null
    const map: Record<string, { variant: "success" | "destructive" | "outline"; key: string }> = {
      RUNNING: { variant: "outline", key: "runStateRunning" },
      SUCCESS: { variant: "success", key: "runStateSuccess" },
      FAILED: { variant: "destructive", key: "runStateFailed" },
      STOPPED: { variant: "destructive", key: "runStateStopped" },
    }
    const m = map[runStatus]
    return m ? <Badge variant={m.variant}>{t(m.key)}</Badge> : null
  }, [runStatus, t])

  // 是否可停止：有运行实例且整体态未到终态
  const canStop = !!runWfId && runStatus !== "SUCCESS" && runStatus !== "FAILED" && runStatus !== "STOPPED"

  // 节点 taskDefId → 节点显示名（自动顶日志/查看日志的 tab 标题用）
  const labelByTaskDef = useCallback(
    (taskDefId: number) => dagNodes.find((n) => n.data.taskId === taskDefId)?.data.label || `#${taskDefId}`,
    [dagNodes],
  )

  // 运行时自动顶「当前运行节点」日志：仅首次出现该实例时开 Tab 并抢焦，已开过不打断用户。
  useEffect(() => {
    const statusEvents = events.filter((e) => e.type === "status")
    if (statusEvents.length === 0) return
    for (const ev of statusEvents) {
      try {
        const u = JSON.parse(ev.data) as { taskId?: string; taskState?: string }
        if (!u.taskId || !u.taskState) continue
        if (u.taskState !== "RUNNING" && u.taskState !== "DISPATCHED") continue
        if (autoOpenedRef.current.has(u.taskId)) continue
        autoOpenedRef.current.add(u.taskId)
        const td = instanceToTaskDef.get(u.taskId)
        openRunTab({
          instanceId: u.taskId,
          taskName: td != null ? labelByTaskDef(td) : name,
          startedAt: new Date().toISOString(),
          kind: "log",
        })
      } catch {
        /* ignore malformed event */
      }
    }
  }, [events, instanceToTaskDef, labelByTaskDef, name, openRunTab])

  // 右键菜单动作（经 context 下发给节点组件）
  const nodeActions = useMemo<NodeActions>(
    () => ({
      canViewLog: (taskDefId) => taskDefToInstance.has(taskDefId),
      onViewLog: (taskDefId, label) => {
        const instanceId = taskDefToInstance.get(taskDefId)
        if (!instanceId) {
          toast.info(t("toastNodeNoRun"))
          return
        }
        openRunTab({ instanceId, taskName: label, startedAt: new Date().toISOString(), kind: "log" })
      },
      onRunNode: (taskId, label) => runSingleTaskNode(taskId, label),
      onDeleteNode: (id) => {
        deleteElements({ nodes: [{ id }] })
      },
      online,
      onRunToNode: (nodeKey) => runWorkflowWithScope("TO_NODE", nodeKey),
      onRunDownstream: (nodeKey) => runWorkflowWithScope("DOWNSTREAM", nodeKey),
    }),
    [taskDefToInstance, openRunTab, deleteElements, t, online, runWorkflowWithScope, runSingleTaskNode],
  )

  // ─── 版本操作 ───────────────────────────────────────
  function toVersionInfo(v: WorkflowDefVersion): VersionInfo {
    return { versionNo: v.versionNo, publishedAt: v.publishedAt, publishedBy: v.publishedBy, remark: v.remark }
  }

  /** DAG 快照 JSON pretty-print，让 Monaco DiffEditor 的逐行 diff 可读。 */
  function prettyJson(s: string | null): string {
    if (!s) return ""
    try {
      return JSON.stringify(JSON.parse(s), null, 2)
    } catch {
      return s ?? ""
    }
  }

  function toDiffInput(v: WorkflowDefVersion): VersionDiffInput {
    return { versionNo: v.versionNo, text: prettyJson(v.dagSnapshotJson), language: "json" }
  }
  async function confirmRollback() {
    if (!rollbackTarget) return
    setRolling(true)
    try {
      const res = await authFetch(`${API_BASE}/api/workflows/${workflowId}/rollback`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ versionNo: rollbackTarget.versionNo }),
      })
      const j = (await res.json()) as ApiResponse<RunResult>
      if (j.code !== 0 || !j.data) throw new Error(String(j.message ?? ""))
      const r = j.data
      if (r.outcome === "EXECUTED") {
        toast.success(t("rollbackSuccess", { vno: rollbackTarget.versionNo }))
        setRollbackTarget(null)
        loadDag(workflowId)
        loadWfDetail()
      } else if (r.outcome === "PENDING_APPROVAL") {
        toast.info(t("rollbackPendingApproval", { id: r.actionId ?? "?" }))
        setRollbackTarget(null)
      } else {
        toast.error(r.message || t("rollbackFailed", { error: "" }))
        setRollbackTarget(null)
      }
    } catch (e: unknown) {
      toast.error(t("rollbackFailed", { error: e instanceof Error ? e.message : String(e) }))
    } finally { setRolling(false) }
  }

  if (loading) return <ViewStatus loading />

  return (
    <div className="flex h-full flex-col">
      {/* 工具栏：运行 / 虚拟节点 / 保存 / 发布 + 状态 */}
      <div className="flex flex-wrap items-center gap-2 p-3">
        <HugeiconsIcon icon={Share08Icon} className="text-primary" />
        <h1 className="text-sm font-medium">{name}</h1>
        {/* 单按钮 Run⇄Stop：运行实例非终态(canStop)显示「停止」,否则显示「运行」（run-state-resume）。 */}
        {canStop ? (
          <Button size="sm" variant="outline" onClick={stopWorkflow} disabled={stopping}>
            <HugeiconsIcon icon={StopIcon} className="size-4" /> {stopping ? t("stopping") : t("stop")}
          </Button>
        ) : (
          <Button size="sm" onClick={() => setRunDialogOpen(true)} disabled={!online || busy}>
            <HugeiconsIcon icon={PlayIcon} className="size-4" /> {t("run")}
          </Button>
        )}
        {runStatusBadge}
        {!online && (
          <span className="text-xs text-muted-foreground">
            {t("notOnlineHint")}
            <button type="button" className="ml-0.5 underline hover:text-foreground" onClick={publish} disabled={busy}>
              {t("publish")}
            </button>
          </span>
        )}
        <div className="ml-auto flex items-center gap-2">
          {online && <Badge variant="success">{t("online")}</Badge>}
          {(dirty || hasDraft) && (
            <Badge variant="outline" className="text-amber-600">
              {dirty ? t("unsavedChanges") : t("unpublishedChanges")}
            </Badge>
          )}
          {online && drift?.drifted && (
            <>
              <Badge variant="outline" className="cursor-help text-amber-600" title={driftTitle}>
                {t("driftBadge", { count: drift.driftedNodes.length })}
              </Badge>
              <Button size="sm" variant="outline" onClick={publish} disabled={busy || dirty} title={t("rePromoteHint")}>
                <HugeiconsIcon icon={RocketIcon} className="size-4" /> {t("rePromote")}
              </Button>
            </>
          )}
          <Button size="sm" variant="outline" onClick={addVirtualNode} disabled={busy}>
            <HugeiconsIcon icon={CircleIcon} className="size-4" /> {t("virtualNode")}
          </Button>
          <Button size="sm" variant="outline" onClick={saveDraft} disabled={busy}>
            <HugeiconsIcon icon={FloppyDiskIcon} className="size-4" /> {t("saveDraft")}
          </Button>
          <Button size="sm" onClick={publish} disabled={busy}>
            <HugeiconsIcon icon={RocketIcon} className="size-4" /> {t("publish")}
          </Button>
        </div>
      </div>

      <div className="flex min-h-0 flex-1">
      <div className="relative min-h-0 flex-1" ref={wrapperRef} onDrop={onDrop} onDragOver={onDragOver}>
        <NodeActionsContext.Provider value={nodeActions}>
          <ReactFlow
            id={`canvas-${workflowId}`}
            nodes={displayNodes}
            edges={edges}
            nodeTypes={nodeTypes}
            onNodesChange={onNodesChange}
            onEdgesChange={onEdgesChange}
            onConnect={onConnect}
            onEdgeContextMenu={(e, edge) => {
              e.preventDefault()
              setEdgeMenu({ x: e.clientX, y: e.clientY, id: edge.id })
            }}
            onPaneClick={() => setEdgeMenu(null)}
            onNodeClick={() => setEdgeMenu(null)}
            onMoveStart={() => setEdgeMenu(null)}
            deleteKeyCode={["Backspace", "Delete"]}
            fitView
            proOptions={{ hideAttribution: true }}
          >
            <Background />
            <Controls />
            <MiniMap pannable zoomable />
          </ReactFlow>
        </NodeActionsContext.Provider>

        {/* 边右键菜单浮层（click-away 关闭）：切换强/弱依赖 + 删除 */}
        {edgeMenu && (() => {
          const edge = edges.find((e) => e.id === edgeMenu.id)
          const cur = (edge?.data as { strength?: string } | undefined)?.strength ?? "STRONG"
          return (
            <>
              <div className="fixed inset-0 z-40" onClick={() => setEdgeMenu(null)} onContextMenu={(e) => { e.preventDefault(); setEdgeMenu(null) }} />
              <div
                className="fixed z-50 min-w-36 rounded-md border bg-popover p-1 text-popover-foreground shadow-md"
                style={{ left: edgeMenu.x, top: edgeMenu.y }}
              >
                <button
                  type="button"
                  className="flex w-full items-center rounded-sm px-2 py-1.5 text-sm outline-none hover:bg-accent disabled:opacity-50"
                  disabled={cur === "WEAK"}
                  onClick={() => setEdgeStrength(edgeMenu.id, "WEAK")}
                >
                  {t("edgeMenuSetWeak")}
                </button>
                <button
                  type="button"
                  className="flex w-full items-center rounded-sm px-2 py-1.5 text-sm outline-none hover:bg-accent disabled:opacity-50"
                  disabled={cur !== "WEAK"}
                  onClick={() => setEdgeStrength(edgeMenu.id, "STRONG")}
                >
                  {t("edgeMenuSetStrong")}
                </button>
                <div className="my-1 h-px bg-border" />
                <button
                  type="button"
                  className="flex w-full items-center rounded-sm px-2 py-1.5 text-sm text-destructive outline-none hover:bg-accent"
                  onClick={() => {
                    deleteElements({ edges: [{ id: edgeMenu.id }] })
                    setEdgeMenu(null)
                  }}
                >
                  {t("edgeMenuDelete")}
                </button>
              </div>
            </>
          )
        })()}
      </div>

        {/* 右：配置 / 版本历史 tab 栏 */}
        <div className="w-72 shrink-0 border-l border-border flex flex-col">
          <div className="flex border-b border-border">
            <button
              onClick={() => setSidebarTab("config")}
              className={cn(
                "flex-1 py-2 text-xs font-medium border-b-2 transition-colors",
                sidebarTab === "config" ? "border-primary text-primary" : "border-transparent text-muted-foreground hover:text-foreground",
              )}
            >
              {t("configTab")}
            </button>
            <button
              onClick={() => setSidebarTab("versions")}
              className={cn(
                "flex-1 py-2 text-xs font-medium border-b-2 transition-colors",
                sidebarTab === "versions" ? "border-primary text-primary" : "border-transparent text-muted-foreground hover:text-foreground",
              )}
            >
              {t("versionsTab")}
            </button>
          </div>
          <DwScroll className="flex-1" innerClassName="overflow-y-auto">
            <div style={{ display: sidebarTab === "config" ? "block" : "none" }}>
              <WorkflowConfigPanel
                name={wfName}
                setName={(v) => setWfName(v)}
                description={wfDesc}
                setDescription={(v) => setWfDesc(v)}
                scheduleType={wfScheduleType}
                setScheduleType={(v) => setWfScheduleType(v)}
                cron={wfCron}
                setCron={(v) => setWfCron(v)}
                priority={wfPriority}
                setPriority={(v) => setWfPriority(v)}
                onDirty={() => setDirty(true)}
                workflowId={workflowId}
                nodes={dagNodes.map((n) => ({ nodeKey: n.id, nodeType: n.data.nodeType, taskId: n.data.taskId, name: n.data.label, posX: null, posY: null }))}
              />
            </div>
            <div style={{ display: sidebarTab === "versions" ? "block" : "none" }}>
              <VersionHistoryPanel
                versions={wfVersions.map(toVersionInfo)}
                currentVersionNo={wfVersions.length > 0 ? wfVersions[0]?.versionNo ?? 0 : 0}
                onView={(v) => {
                  const ver = wfVersions.find((x) => x.versionNo === v.versionNo)
                  if (ver) setViewingVersion(ver)
                }}
                onRollback={(v) => {
                  const ver = wfVersions.find((x) => x.versionNo === v.versionNo)
                  if (ver) setRollbackTarget(ver)
                }}
                onDiff={(a, b) => {
                  const va = wfVersions.find((x) => x.versionNo === a.versionNo)
                  const vb = wfVersions.find((x) => x.versionNo === b.versionNo)
                  if (va && vb) setDiffVersions([va, vb])
                }}
              />
            </div>
          </DwScroll>
        </div>
      </div>

      {/* 运行日志 Tabs 区（每节点一条实例日志流，与任务编辑器同一套零件） */}
      {runTabs.length > 0 && (
        <>
          <div
            onPointerDown={onLogResizeDown}
            role="separator"
            aria-orientation="horizontal"
            aria-label={t("resizeLogPanel")}
            className="group/logresize relative flex h-2 shrink-0 cursor-row-resize touch-none items-center justify-center border-t border-border"
          >
            <div className="h-0.5 w-12 rounded-full bg-border/0 transition-colors group-hover/logresize:bg-border" />
          </div>
          <div className="shrink-0" style={{ height: logHeight }}>
            <RunLogsTabs
              tabs={runTabs}
              activeId={activeRunTab}
              onActivate={setActiveRunTab}
              onClose={closeRunTab}
              onCloseOthers={closeOtherRunTabs}
              onCloseRight={closeRunTabsRight}
              onCloseLeft={closeRunTabsLeft}
              onCloseAll={closeAllRunTabs}
              locale={locale}
            />
          </div>
        </>
      )}

      {/* 版本弹窗 */}
      <WorkflowVersionDetailDialog
        open={viewingVersion !== null}
        onClose={() => setViewingVersion(null)}
        version={viewingVersion}
      />
      <VersionDiffDialog
        open={diffVersions !== null}
        onClose={() => setDiffVersions(null)}
        v1={diffVersions ? toDiffInput(diffVersions[0]) : null}
        v2={diffVersions ? toDiffInput(diffVersions[1]) : null}
      />
      <RollbackConfirmDialog
        open={rollbackTarget !== null}
        onClose={() => setRollbackTarget(null)}
        onConfirm={confirmRollback}
        version={rollbackTarget ? {
          id: rollbackTarget.id,
          taskId: rollbackTarget.workflowId,
          versionNo: rollbackTarget.versionNo,
          name: rollbackTarget.name,
          type: "",
          content: rollbackTarget.dagSnapshotJson ?? "",
          datasourceId: null,
          targetDatasourceId: null,
          paramsJson: null,
          timeoutSec: null,
          retryMax: null,
          priority: 0,
          description: rollbackTarget.description,
          remark: rollbackTarget.remark,
          publishedBy: rollbackTarget.publishedBy,
          publishedAt: rollbackTarget.publishedAt,
        } : null}
        hasDraft={hasDraft}
        rolling={rolling}
      />

      {/* 运行范围 Dialog（工具栏「运行」入口）：FULL / TO_NODE / DOWNSTREAM / ONLY_NODE + 目标节点 */}
      <Dialog open={runDialogOpen} onOpenChange={setRunDialogOpen}>
        <DialogContent className="max-w-sm">
          <DialogHeader>
            <DialogTitle>{t("runDialogTitle")}</DialogTitle>
            <DialogDescription>{t("runDialogDesc")}</DialogDescription>
          </DialogHeader>
          <div className="flex flex-col gap-3 py-1">
            <div className="flex flex-col gap-1.5">
              <label className="text-xs font-medium text-muted-foreground">{t("runDialogScope")}</label>
              <div className="flex flex-wrap gap-2">
                {([
                  { s: "FULL" as const, label: t("runScopeFull") },
                  { s: "TO_NODE" as const, label: t("runScopeToNode") },
                  { s: "DOWNSTREAM" as const, label: t("runScopeDownstream") },
                  { s: "ONLY_NODE" as const, label: t("runScopeOnlyNode") },
                ]).map(({ s, label }) => (
                  <button
                    key={s}
                    type="button"
                    className={cn(
                      "rounded-md border px-3 py-1.5 text-xs transition-colors",
                      runScope === s ? "border-primary bg-primary/10 text-primary" : "border-border hover:bg-accent",
                    )}
                    onClick={() => { setRunScope(s); setRunTargetKey("") }}
                  >
                    {label}
                  </button>
                ))}
              </div>
            </div>
            {runScope !== "FULL" && (
              <div className="flex flex-col gap-1.5">
                <label className="text-xs font-medium text-muted-foreground">{t("runDialogTarget")}</label>
                <DropdownSelect value={runTargetKey} onChange={setRunTargetKey} options={runTargetOptions} />
              </div>
            )}
          </div>
          <DialogFooter>
            <DialogClose render={<Button variant="ghost" />}>{t("cancel")}</DialogClose>
            <Button onClick={confirmRunDialog} disabled={runScope !== "FULL" && !runTargetKey}>
              {t("run")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}

/** 画布子 Tab（自带 ReactFlowProvider，多子 Tab keep-alive 各自独立）。 */
function WorkflowCanvasPane({
  workflowId,
  name,
}: {
  workflowId: number
  name: string
}) {
  return (
    <ReactFlowProvider>
      <CanvasInner workflowId={workflowId} name={name} />
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
  const t = useTranslations("workflowCanvas")
  const tc = useTranslations("catalog")
  const [tabs, setTabs] = useState<SubTab[]>([])
  const [activeKey, setActiveKey] = useState<string | null>(null)
  // closeTab 用 ref 读最新 activeKey，避免 useCallback 闭包陈旧
  const activeKeyRef = useRef<string | null>(null)
  activeKeyRef.current = activeKey

  // 从 workspace store 读取任务 dirty 状态（供子 Tab 显示黄点 + 关闭拦截）
  const taskDirtyState = useWorkspaceStore((s) => s.taskDirtyState)

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

  // 关闭拦截：任务编辑子 Tab 有未保存改动时弹框确认
  const [closeConfirm, setCloseConfirm] = useState<{ key: string; label: string } | null>(null)

  const handleClose = useCallback((key: string) => {
    const tab = tabs.find((t) => tabKey(t) === key)
    if (!tab) return
    const isDirty = tab.kind === "editor" && !!taskDirtyState[tab.id]
    if (isDirty) {
      setCloseConfirm({ key, label: tab.name })
    } else {
      closeTab(key)
    }
  }, [tabs, taskDirtyState, closeTab])

  const confirmClose = useCallback(() => {
    if (closeConfirm) {
      closeTab(closeConfirm.key)
      setCloseConfirm(null)
    }
  }, [closeConfirm, closeTab])

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
    if (initialWorkflowId != null) openTab({ kind: "canvas", id: initialWorkflowId, name: t("workflowFallbackName") })
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
        useCatalogTreeStore.getState().upsertTask(j.data)
        toast.success(t("toastTaskDraftCreated"))
      } else {
        toast.error(j.message)
      }
    } catch {
      toast.error(t("toastCreateFailed"))
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
        useCatalogTreeStore.getState().upsertWorkflow(j.data)
        toast.success(t("toastWorkflowDraftCreated"))
      } else {
        toast.error(j.message)
      }
    } catch {
      toast.error(t("toastCreateFailed"))
    } finally {
      setCreating(false)
    }
  }

  const stripTabs: TabStripItem[] = tabs.map((tab) => {
    const isDirty = tab.kind === "editor" && !!taskDirtyState[tab.id]
    return {
      id: tabKey(tab),
      label: tab.name,
      icon: tab.kind === "canvas" ? Share08Icon : Task01Icon,
      suffix: isDirty ? (
        <span
          className="ml-1 size-1.5 shrink-0 rounded-full bg-warning"
          aria-label={tc("statusDirty")}
          title={tc("statusDirty")}
        />
      ) : undefined,
    }
  })

  return (
    <div className="flex h-full gap-3 p-3">
      {/* 左侧常驻类目树 —— 外层 relative + pr-1.5，handle 落在 card 右侧 gap 中（同 AgentRail 结构） */}
      <div className="relative flex shrink-0 flex-col pr-1.5">
        <motion.div
          className="flex h-full flex-col overflow-hidden rounded-[var(--radius-lg)] border bg-card shadow-lg"
          style={{ width: catalogWidthProp }}
        >
          <DwScroll className="min-h-0 flex-1 p-3">
            <CatalogTree
              draggableTasksToCanvas
              enableMove
              showTagFilter
              onOpenTask={(id, name) => openTab({ kind: "editor", id, name })}
              onOpenWorkflow={(id, name) => openTab({ kind: "canvas", id, name })}
              onDeleteTask={(id) => closeTab(tabKey({ kind: "editor", id, name: "" }))}
              onDeleteWorkflow={(id) => closeTab(tabKey({ kind: "canvas", id, name: "" }))}
              onRenameTask={(id, name) => setTabs((prev) => prev.map((t) => t.kind === "editor" && t.id === id ? { ...t, name } : t))}
              onRenameWorkflow={(id, name) => setTabs((prev) => prev.map((t) => t.kind === "canvas" && t.id === id ? { ...t, name } : t))}
            />
          </DwScroll>
        </motion.div>
        {/* 右缘分割线拖拽 */}
        <div
          onPointerDown={onCatalogResizeDown}
          role="separator"
          aria-orientation="vertical"
          aria-label={t("resizeCatalogPanel")}
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
            onClose={handleClose}
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
              {t("emptyState")}
            </div>
          ) : (
            tabs.map((t) => (
              <div
                key={tabKey(t)}
                className={tabKey(t) === activeKey ? "absolute inset-0" : "absolute inset-0 hidden"}
              >
                {t.kind === "canvas" ? (
                  <WorkflowCanvasPane workflowId={t.id} name={t.name} />
                ) : (
                  <TaskEditorPane
                    taskId={t.id}
                    onNameChange={(name) => setTabs((prev) => prev.map((s) => s.kind === "editor" && s.id === t.id ? { ...s, name } : s))}
                  />
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
            <DialogTitle>{t("newTask")}</DialogTitle>
          </DialogHeader>
          <Input
            autoFocus
            value={taskName}
            placeholder={t("taskNamePlaceholder")}
            onChange={(e) => setTaskName(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter") {
                e.preventDefault()
                submitCreateTask()
              }
            }}
          />
          <div className="flex items-center gap-2">
            <span className="text-xs text-muted-foreground">{t("type")}</span>
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
            <DialogClose render={<Button variant="ghost" />}>{t("cancel")}</DialogClose>
            <Button onClick={submitCreateTask} disabled={creating}>
              {t("create")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* 新建工作流 Dialog */}
      <Dialog open={wfDialog} onOpenChange={setWfDialog}>
        <DialogContent className="max-w-sm">
          <DialogHeader>
            <DialogTitle>{t("newWorkflow")}</DialogTitle>
          </DialogHeader>
          <Input
            autoFocus
            value={wfName}
            placeholder={t("workflowNamePlaceholder")}
            onChange={(e) => setWfName(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter") {
                e.preventDefault()
                submitCreateWorkflow()
              }
            }}
          />
          <DialogFooter>
            <DialogClose render={<Button variant="ghost" />}>{t("cancel")}</DialogClose>
            <Button onClick={submitCreateWorkflow} disabled={creating}>
              {t("create")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* 关闭拦截确认弹框 */}
      <Dialog
        open={!!closeConfirm}
        onOpenChange={(open) => {
          if (!open) setCloseConfirm(null)
        }}
      >
        <DialogContent className="max-w-sm">
          <DialogHeader>
            <DialogTitle>{t("confirmCloseTitle")}</DialogTitle>
            <DialogDescription>
              {t("confirmCloseDesc", { label: closeConfirm?.label ?? "" })}
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <DialogClose render={<Button variant="ghost" />}>{t("cancel")}</DialogClose>
            <Button variant="destructive" onClick={confirmClose}>
              {t("discardChanges")}
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
