"use client"

/**
 * 可复用类目树 <CatalogTree>（task-workflow-catalog）。
 *
 * 文件夹树（唯一归属）+ 标签横切过滤 + 本地搜索，任务与工作流统一混挂。受控展开态由
 * useCatalogTreeStore 持有。首发落点为数据开发 IDE 左侧常驻面板。
 *
 * 两种拖拽语义（以 drop target 区分，见 D7）：
 *   ① 树内把叶子拖入文件夹 → 移动归属（PATCH /api/{tasks|workflows}/{id}/catalog）。
 *      载荷类型 application/dw-catalog-move，文件夹行作为 drop target 读取。
 *   ② 任务叶子拖到画布（ReactFlow pane）→ 建 DAG 节点。
 *      载荷类型 application/dw-task，仅当 draggableTasksToCanvas 时附加；画布 onDrop 读取。
 * 一个任务叶子可同时携带两种载荷，由落点决定生效哪一种——互不串味。
 *
 * 交互式输入/确认统一走 base 风格 Dialog（建文件夹 / 重命名 / 删除），不使用原生 window.prompt。
 */
import { AnimatePresence, motion } from "motion/react"
import { useCallback, useEffect, useMemo, useState, type ReactNode } from "react"
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  ArrowDown01Icon,
  ArrowRight01Icon,
  Delete02Icon,
  Calendar03Icon,
  Folder01Icon,
  FolderAddIcon,
  FolderOpenIcon,
  InboxIcon,
  MoveToIcon,
  PencilEdit01Icon,
  RefreshIcon,
  Share08Icon,
  Tag01Icon,
  Task01Icon,
} from "@hugeicons/core-free-icons"
import { toast } from "sonner"

import {
  API_BASE,
  authFetch,
  type ApiResponse,
  type CatalogTree as CatalogTreeData,
  type CatalogTreeNode,
  type Tag,
  type TaskDef,
  type WorkflowDef,
} from "@/lib/types"
import { useCatalogTreeStore } from "@/lib/workspace/catalog-tree-store"
import { useWorkspaceStore } from "@/lib/workspace/store"
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Input } from "@/components/ui/input"
import { Button } from "@/components/ui/button"
import { DropdownSelect } from "@/components/ui/select"
import { DwScroll } from "@/components/ui/dw-scroll"
import {
  ContextMenu,
  ContextMenuTrigger,
  ContextMenuContent,
  ContextMenuItem,
  ContextMenuSeparator,
} from "@/components/ui/context-menu"
import { BackfillDialog } from "@/components/workspace/views/ops/backfill-dialog"

const MOVE_MIME = "application/dw-catalog-move"
const TASK_MIME = "application/dw-task"
const UNCATEGORIZED = -1

/** motion  enter/exit 动画参数：淡入+下滑进、淡出+上滑出。 */
const itemMotion = {
  initial: { opacity: 0, height: 0, y: -6 },
  animate: { opacity: 1, height: "auto", y: 0 },
  exit: { opacity: 0, height: 0, y: -6 },
  transition: { type: "spring", stiffness: 500, damping: 30, mass: 0.8 },
} as const

/** 新建/移动高亮闪烁（bg 从 primary/15 过渡到透明）。 */
const highlightClass = "catalog-item-enter"

// 树结构纯函数（insertFolderIntoTree / removeFolderFromTree / renameFolderInTree）与乐观更新
// action 已移至 catalog-tree-store.ts；本组件经 store action 调用（insertFolder / removeFolder / renameFolder）。

interface MovePayload {
  kind: "task" | "workflow"
  id: number
}

/**
 * 统一 Dialog 状态。所有写交互（建文件夹 / 文件夹改名删除 / 在此新建任务工作流 /
 * 叶子改名删除）经此状态机驱动同一 base 风格 Dialog，不使用原生 prompt/confirm。
 */
type DialogState =
  | { mode: "closed" }
  | { mode: "folder"; parentId: number | null; name: string }
  | { mode: "folder-rename"; id: number; name: string }
  | { mode: "folder-delete"; id: number; name: string }
  | { mode: "create-task"; parentId: number | null; name: string; taskType: "SQL" | "SHELL" }
  | { mode: "create-workflow"; parentId: number | null; name: string }
  | { mode: "rename"; kind: "task" | "workflow"; id: number; name: string }
  | { mode: "delete"; kind: "task" | "workflow"; id: number; name: string }
  | { mode: "move"; kind: "task" | "workflow"; id: number; name: string; selectedId: number | null }

export interface CatalogTreeProps {
  /** 项目 ID（默认 1）。 */
  projectId?: number
  /** 任务叶子是否额外携带 application/dw-task 载荷（供画布建节点）。 */
  draggableTasksToCanvas?: boolean
  /** 是否允许树内拖动叶子改归属（文件夹作 drop target）。 */
  enableMove?: boolean
  /** 是否显示顶部标签过滤 chips。 */
  showTagFilter?: boolean
  /** 点击任务叶子（id + 名称）—— IDE 壳据此开编辑子 Tab。 */
  onOpenTask?: (id: number, name: string) => void
  /** 点击工作流叶子（id + 名称）—— IDE 壳据此开画布子 Tab。 */
  onOpenWorkflow?: (id: number, name: string) => void
  /** 类目树中删除任务后 —— IDE 壳据此关闭对应编辑子 Tab。 */
  onDeleteTask?: (id: number) => void
  /** 类目树中删除工作流后 —— IDE 壳据此关闭对应画布子 Tab。 */
  onDeleteWorkflow?: (id: number) => void
  /** 类目树中重命名 —— IDE 壳据此更新子 Tab 标题。 */
  onRenameTask?: (id: number, name: string) => void
  /** 类目树中重命名工作流 —— IDE 壳据此更新子 Tab 标题。 */
  onRenameWorkflow?: (id: number, name: string) => void
  className?: string
}

/**
 * 移动归属目录选择器（move dialog 内容）：缩进文件夹树 + 顶部「未分类」项，单选高亮。
 * 展开态用局部 state（不污染主树 store.expanded），数据复用 store.tree。
 */
function MoveFolderPicker({
  selectedId,
  onSelect,
}: {
  selectedId: number | null
  onSelect: (id: number | null) => void
}) {
  const t = useTranslations()
  const tree = useCatalogTreeStore((s) => s.tree)
  const [open, setOpen] = useState<Record<number, boolean>>({})

  const renderRow = (node: CatalogTreeNode, depth: number): ReactNode => {
    const children = node.children ?? []
    const hasChildren = children.length > 0
    const isOpen = !!open[node.id]
    const selected = selectedId === node.id
    return (
      <div key={node.id}>
        <div
          role="button"
          tabIndex={0}
          onClick={() => onSelect(node.id)}
          className={`flex cursor-pointer items-center gap-1.5 rounded-md py-1.5 pr-2 text-sm hover:bg-accent ${
            selected ? "bg-accent" : ""
          }`}
          style={{ paddingLeft: depth * 22 + 4 }}
        >
          <button
            type="button"
            tabIndex={-1}
            onClick={(e) => {
              e.stopPropagation()
              if (hasChildren) setOpen((o) => ({ ...o, [node.id]: !o[node.id] }))
            }}
            className="flex size-4 shrink-0 items-center justify-center text-muted-foreground"
          >
            {hasChildren ? (
              <HugeiconsIcon icon={isOpen ? ArrowDown01Icon : ArrowRight01Icon} className="size-4" />
            ) : null}
          </button>
          <HugeiconsIcon
            icon={isOpen ? FolderOpenIcon : Folder01Icon}
            className="size-4 shrink-0 text-amber-500"
          />
          <span className="truncate font-medium">{node.name}</span>
          <span className="ml-auto shrink-0 text-xs text-muted-foreground">
            {node.taskCount + node.workflowCount || ""}
          </span>
        </div>
        {isOpen && children.map((c) => renderRow(c, depth + 1))}
      </div>
    )
  }

  return (
    <DwScroll className="max-h-64">
      <div
        role="button"
        tabIndex={0}
        onClick={() => onSelect(null)}
        className={`flex cursor-pointer items-center gap-1.5 rounded-md px-1 py-1.5 pr-2 text-sm hover:bg-accent ${
          selectedId === null ? "bg-accent" : ""
        }`}
      >
        <span className="size-4 shrink-0" aria-hidden />
        <HugeiconsIcon icon={InboxIcon} className="size-4 shrink-0 text-muted-foreground" />
        <span className="truncate font-medium text-muted-foreground">{t("catalog.uncategorized")}</span>
      </div>
      {(tree?.roots ?? []).map((r) => renderRow(r, 0))}
    </DwScroll>
  )
}

export function CatalogTree({
  projectId = 1,
  draggableTasksToCanvas = false,
  enableMove = true,
  showTagFilter = true,
  onOpenTask,
  onOpenWorkflow,
  onDeleteTask,
  onDeleteWorkflow,
  onRenameTask,
  onRenameWorkflow,
  className,
}: CatalogTreeProps) {
  const t = useTranslations()
  const {
    expanded, toggleExpand, expand, selectedTagIds, toggleTag, clearTags,
    tree, tasks, workflows, loading,
    setData, upsertTask, upsertWorkflow, updateTask, updateWorkflow,
    removeTask, removeWorkflow, insertFolder, removeFolder, renameFolder: renameFolderInStore,
  } = useCatalogTreeStore()

  // 从 workspace store 读取任务 dirty 状态（供叶子节点显示黄点）
  const taskDirtyState = useWorkspaceStore((s) => s.taskDirtyState)

  const [tags, setTags] = useState<Tag[]>([])
  const [visibleTaskIds, setVisibleTaskIds] = useState<Set<number> | null>(null)
  const [visibleWorkflowIds, setVisibleWorkflowIds] = useState<Set<number> | null>(null)
  const [dropTarget, setDropTarget] = useState<number | null>(null)
  const [tick, setTick] = useState(0)
  const reload = useCallback(() => setTick((t) => t + 1), [])
  /** 高亮新进/移动的节点（key = `folder-${id}` | `task-${id}` | `workflow-${id}`），动画结束后清除。 */
  const [highlightedKeys, setHighlightedKeys] = useState<Set<string>>(new Set())
  const flashHighlight = useCallback((keys: string[]) => {
    if (!keys.length) return
    setHighlightedKeys((prev) => new Set([...prev, ...keys]))
    setTimeout(() => {
      setHighlightedKeys((prev) => {
        const next = new Set(prev)
        for (const k of keys) next.delete(k)
        return next
      })
    }, 900)
  }, [])

  // ── in-flight 锁（兜底）：写请求未完成期间锁定该节点（key 同 render key：folder-/task-/workflow-id），
  //    防止用户对「已发出删除/改名/移动请求但尚未返回」的节点重复操作（再删/再拖/再改名）造成并发歧义。──
  const [pendingKeys, setPendingKeys] = useState<Set<string>>(new Set())
  const lock = useCallback((key: string) => {
    setPendingKeys((s) => {
      const n = new Set(s)
      n.add(key)
      return n
    })
  }, [])
  const unlock = useCallback((key: string) => {
    setPendingKeys((s) => {
      if (!s.has(key)) return s
      const n = new Set(s)
      n.delete(key)
      return n
    })
  }, [])

  // ── 本地搜索（D7）：子串过滤叶子 + 命中祖先自动展开；清空恢复 store 原展开态 ──
  const [query, setQuery] = useState("")
  const q = query.trim().toLowerCase()
  const searching = q !== ""

  // ── 统一 Dialog ──
  const [dialog, setDialog] = useState<DialogState>({ mode: "closed" })
  const closeDialog = useCallback(() => setDialog({ mode: "closed" }), [])
  // 就地补数据：右键叶子 → 预填目标打开补数据弹窗
  const [backfill, setBackfill] = useState<{ type: "task" | "workflow"; id: number; name: string } | null>(null)

  // ── 基础数据：树 + 任务 + 工作流 + 标签 ──
  useEffect(() => {
    let alive = true
    setData({ loading: true })
    Promise.all([
      authFetch(`${API_BASE}/api/catalog/tree?projectId=${projectId}`, { cache: "no-store" })
        .then((r) => r.json() as Promise<ApiResponse<CatalogTreeData>>)
        .then((j) => (j.code === 0 ? j.data : null)),
      authFetch(`${API_BASE}/api/tasks?size=500`, { cache: "no-store" })
        .then((r) => r.json() as Promise<ApiResponse<{ content: TaskDef[] }>>)
        .then((j) => (j.code === 0 ? j.data?.content ?? [] : [])),
      authFetch(`${API_BASE}/api/workflows?size=500`, { cache: "no-store" })
        .then((r) => r.json() as Promise<ApiResponse<{ content: WorkflowDef[] }>>)
        .then((j) => (j.code === 0 ? j.data?.content ?? [] : [])),
      authFetch(`${API_BASE}/api/tags?projectId=${projectId}`, { cache: "no-store" })
        .then((r) => r.json() as Promise<ApiResponse<Tag[]>>)
        .then((j) => (j.code === 0 ? j.data ?? [] : [])),
    ])
      .then(([tr, tks, wfs, tgs]) => {
        if (!alive) return
        setData({ tree: tr, tasks: tks, workflows: wfs, loading: false })
        setTags(tgs)
      })
      .finally(() => alive && setData({ loading: false }))
    return () => {
      alive = false
    }
  }, [projectId, tick])

  // ── 标签过滤：取选中标签的资产 id 并集，null=不过滤 ──
  useEffect(() => {
    let alive = true
    if (selectedTagIds.length === 0) {
      setVisibleTaskIds(null)
      setVisibleWorkflowIds(null)
      return
    }
    Promise.all(
      selectedTagIds.flatMap((tagId) => [
        authFetch(`${API_BASE}/api/tasks?tagId=${tagId}&size=500`, { cache: "no-store" })
          .then((r) => r.json() as Promise<ApiResponse<{ content: TaskDef[] }>>)
          .then((j) => ({ kind: "task" as const, ids: (j.data?.content ?? []).map((t) => t.id) })),
        authFetch(`${API_BASE}/api/workflows?tagId=${tagId}&size=500`, { cache: "no-store" })
          .then((r) => r.json() as Promise<ApiResponse<{ content: WorkflowDef[] }>>)
          .then((j) => ({ kind: "workflow" as const, ids: (j.data?.content ?? []).map((w) => w.id) })),
      ]),
    ).then((results) => {
      if (!alive) return
      const taskSet = new Set<number>()
      const wfSet = new Set<number>()
      for (const r of results) {
        for (const id of r.ids) (r.kind === "task" ? taskSet : wfSet).add(id)
      }
      setVisibleTaskIds(taskSet)
      setVisibleWorkflowIds(wfSet)
    })
    return () => {
      alive = false
    }
  }, [selectedTagIds, projectId, tick])

  // ── 搜索命中集（null=不过滤）──
  const matchedTaskIds = useMemo(() => {
    if (!searching) return null
    const s = new Set<number>()
    for (const t of tasks) if (t.name.toLowerCase().includes(q)) s.add(t.id)
    return s
  }, [tasks, q, searching])

  const matchedWorkflowIds = useMemo(() => {
    if (!searching) return null
    const s = new Set<number>()
    for (const w of workflows) if (w.name.toLowerCase().includes(q)) s.add(w.id)
    return s
  }, [workflows, q, searching])

  // ── 文件夹 → 祖先链（含自身），用于搜索时自动展开命中叶子的祖先 ──
  const folderAncestors = useMemo(() => {
    const map = new Map<number, number[]>()
    const walk = (node: CatalogTreeNode, chain: number[]) => {
      const c = [...chain, node.id]
      map.set(node.id, c)
      for (const child of (node.children ?? [])) walk(child, c)
    }
    if (tree) for (const root of tree.roots) walk(root, [])
    return map
  }, [tree])

  // 搜索时强制展开的文件夹集 = 命中叶子所属文件夹及其全部祖先（同时兼作「可见文件夹」集）
  const searchFolders = useMemo(() => {
    if (!searching) return null
    const s = new Set<number>()
    const addChain = (folderId: number | null | undefined) => {
      if (folderId == null || folderId === UNCATEGORIZED) return
      for (const anc of folderAncestors.get(folderId) ?? []) s.add(anc)
    }
    for (const t of tasks) if (matchedTaskIds?.has(t.id)) addChain(t.catalogNodeId)
    for (const w of workflows) if (matchedWorkflowIds?.has(w.id)) addChain(w.catalogNodeId)
    return s
  }, [searching, tasks, workflows, matchedTaskIds, matchedWorkflowIds, folderAncestors])

  // ── 按归属分组（应用标签可见集 + 搜索命中集双重过滤）──
  const tasksByNode = useMemo(() => {
    const map = new Map<number, TaskDef[]>()
    for (const t of tasks) {
      if (visibleTaskIds && !visibleTaskIds.has(t.id)) continue
      if (matchedTaskIds && !matchedTaskIds.has(t.id)) continue
      const key = t.catalogNodeId ?? UNCATEGORIZED
      ;(map.get(key) ?? map.set(key, []).get(key)!).push(t)
    }
    return map
  }, [tasks, visibleTaskIds, matchedTaskIds])

  const workflowsByNode = useMemo(() => {
    const map = new Map<number, WorkflowDef[]>()
    for (const w of workflows) {
      if (visibleWorkflowIds && !visibleWorkflowIds.has(w.id)) continue
      if (matchedWorkflowIds && !matchedWorkflowIds.has(w.id)) continue
      const key = w.catalogNodeId ?? UNCATEGORIZED
      ;(map.get(key) ?? map.set(key, []).get(key)!).push(w)
    }
    return map
  }, [workflows, visibleWorkflowIds, matchedWorkflowIds])

  // ── 移动归属（树内拖放）：乐观更新 catalogNodeId，展开目标文件夹，高亮新位置 ──
  //    失败局部回滚（恢复原 catalogNodeId），不刷整树；in-flight 期间锁节点防重复拖拽。
  const move = useCallback(
    async (payload: MovePayload, targetNodeId: number | null) => {
      const base = payload.kind === "task" ? "tasks" : "workflows"
      const key = `${payload.kind}-${payload.id}`
      const store = useCatalogTreeStore.getState()
      const prev = (payload.kind === "task" ? store.tasks : store.workflows).find(
        (x) => x.id === payload.id,
      )?.catalogNodeId
      const rollback = () => {
        if (prev !== undefined) {
          if (payload.kind === "task") updateTask(payload.id, { catalogNodeId: prev })
          else updateWorkflow(payload.id, { catalogNodeId: prev })
        }
      }
      // 乐观更新：直接改 store
      if (payload.kind === "task") updateTask(payload.id, { catalogNodeId: targetNodeId })
      else updateWorkflow(payload.id, { catalogNodeId: targetNodeId })
      if (targetNodeId != null) expand(targetNodeId)
      flashHighlight([key])
      lock(key)
      try {
        const res = await authFetch(`${API_BASE}/api/${base}/${payload.id}/catalog`, {
          method: "PATCH",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ catalogNodeId: targetNodeId }),
        })
        const json = (await res.json()) as ApiResponse<unknown>
        if (json.code === 0) {
          toast.success(t("catalog.toastMoved"))
        } else {
          toast.error(json.message || t("catalog.toastMoveFailed"))
          rollback()
        }
      } catch {
        toast.error(t("catalog.toastMoveFailed"))
        rollback()
      } finally {
        unlock(key)
      }
    },
    [expand, lock, unlock, updateTask, updateWorkflow, flashHighlight, t],
  )

  const onFolderDrop = useCallback(
    (e: React.DragEvent, targetNodeId: number | null) => {
      e.preventDefault()
      e.stopPropagation()
      setDropTarget(null)
      const raw = e.dataTransfer.getData(MOVE_MIME)
      if (!raw) return
      try {
        move(JSON.parse(raw) as MovePayload, targetNodeId)
      } catch {
        /* ignore malformed payload */
      }
    },
    [move],
  )

  // ── 写操作（名称由 Dialog 收集，无原生 prompt）──
  const createFolder = useCallback(
    async (parentId: number | null, name: string) => {
      if (!name.trim()) return
      try {
        const res = await authFetch(`${API_BASE}/api/catalog/nodes`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ projectId, parentId, name: name.trim() }),
        })
        const json = (await res.json()) as ApiResponse<CatalogTreeNode>
        if (json.code === 0 && json.data) {
          const newNode = json.data
          insertFolder(parentId, newNode)
          if (parentId != null) expand(parentId)
          flashHighlight([`folder-${newNode.id}`])
          toast.success(t("catalog.toastFolderCreated"))
        } else {
          toast.error(json.message || t("catalog.toastCreateFailed"))
        }
      } catch {
        toast.error(t("catalog.toastCreateFailed"))
      }
    },
    [projectId, expand, flashHighlight, t],
  )

  // 重命名叶子：乐观更新 name，失败局部回滚（恢复原名），不刷整树；in-flight 期间锁节点。
  const renameLeaf = useCallback(
    async (kind: "task" | "workflow", id: number, name: string) => {
      if (!name.trim()) return
      const base = kind === "task" ? "tasks" : "workflows"
      const key = `${kind}-${id}`
      const store = useCatalogTreeStore.getState()
      const prev = (kind === "task" ? store.tasks : store.workflows).find((x) => x.id === id)?.name
      const rollback = () => {
        if (prev !== undefined) {
          if (kind === "task") updateTask(id, { name: prev })
          else updateWorkflow(id, { name: prev })
        }
      }
      // 乐观更新
      if (kind === "task") updateTask(id, { name: name.trim() })
      else updateWorkflow(id, { name: name.trim() })
      flashHighlight([key])
      lock(key)
      try {
        const res = await authFetch(`${API_BASE}/api/${base}/${id}`, {
          method: "PUT",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ name: name.trim() }),
        })
        const json = (await res.json()) as ApiResponse<unknown>
        if (json.code === 0) {
          // 后端改名成功 → 通知 IDE 壳同步子 Tab 标题（放成功后，避免回滚导致壳与 store 不一致）
          if (kind === "task") onRenameTask?.(id, name.trim())
          else onRenameWorkflow?.(id, name.trim())
          toast.success(t("catalog.toastRenamed"))
        } else {
          toast.error(json.message || t("catalog.toastRenameFailed"))
          rollback()
        }
      } catch {
        toast.error(t("catalog.toastRenameFailed"))
        rollback()
      } finally {
        unlock(key)
      }
    },
    [lock, unlock, updateTask, updateWorkflow, flashHighlight, t],
  )

  // 删除叶子：确认式（后端成功才 remove 节点）。失败只 toast、不动树——前端从未改过，天然自洽，
  // 无需整树重拉；in-flight 期间锁节点，防用户重复删除/拖拽同一节点。
  const deleteLeaf = useCallback(
    async (kind: "task" | "workflow", id: number) => {
      const base = kind === "task" ? "tasks" : "workflows"
      const key = `${kind}-${id}`
      lock(key)
      try {
        const res = await authFetch(`${API_BASE}/api/${base}/${id}`, { method: "DELETE" })
        const json = (await res.json()) as ApiResponse<unknown>
        if (json.code === 0) {
          // 后端删除成功 → 才移除前端节点（motion exit 动画播放）+ 通知 IDE 壳关闭对应子 Tab
          if (kind === "task") {
            removeTask(id)
            onDeleteTask?.(id)
          } else {
            removeWorkflow(id)
            onDeleteWorkflow?.(id)
          }
          toast.success(t("catalog.toastDeleted"))
        } else {
          toast.error(json.message || t("catalog.toastDeleteFailed"))
        }
      } catch {
        toast.error(t("catalog.toastDeleteFailed"))
      } finally {
        unlock(key)
      }
    },
    [lock, unlock, removeTask, removeWorkflow, t],
  )

  // 在此新建任务/工作流：乐观加入本地数组，并开子 Tab。
  const createLeaf = useCallback(
    async (
      kind: "task" | "workflow",
      parentId: number | null,
      name: string,
      taskType: "SQL" | "SHELL",
    ) => {
      if (!name.trim()) return
      const base = kind === "task" ? "tasks" : "workflows"
      const body =
        kind === "task"
          ? { name: name.trim(), type: taskType, content: "", catalogNodeId: parentId }
          : { name: name.trim(), catalogNodeId: parentId }
      try {
        const res = await authFetch(`${API_BASE}/api/${base}`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(body),
        })
        const json = (await res.json()) as ApiResponse<TaskDef | WorkflowDef>
        if (json.code === 0 && json.data) {
          const newItem = json.data
          // 乐观添加
          if (kind === "task") {
            upsertTask(newItem as TaskDef)
          } else {
            upsertWorkflow(newItem as WorkflowDef)
          }
          if (parentId != null) expand(parentId)
          flashHighlight([`${kind}-${(newItem as TaskDef).id}`])
          toast.success(
            kind === "task" ? t("catalog.toastTaskDraftCreated") : t("catalog.toastWorkflowDraftCreated"),
          )
          if (kind === "task") onOpenTask?.(newItem.id, newItem.name)
          else onOpenWorkflow?.(newItem.id, newItem.name)
        } else {
          toast.error(json.message || t("catalog.toastCreateFailed"))
        }
      } catch {
        toast.error(t("catalog.toastCreateFailed"))
      }
    },
    [onOpenTask, onOpenWorkflow, expand, flashHighlight, t],
  )

  // 文件夹改名：乐观更新树内名称。
  const renameFolder = useCallback(
    async (id: number, name: string) => {
      if (!name.trim()) return
      try {
        const res = await authFetch(`${API_BASE}/api/catalog/nodes/${id}`, {
          method: "PATCH",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ name: name.trim() }),
        })
        const json = (await res.json()) as ApiResponse<CatalogTreeNode>
        if (json.code === 0) {
          renameFolderInStore(id, name.trim())
          toast.success(t("catalog.toastRenamed"))
          flashHighlight([`folder-${id}`])
        } else {
          toast.error(json.message || t("catalog.toastRenameFailed"))
        }
      } catch {
        toast.error(t("catalog.toastRenameFailed"))
      }
    },
    [flashHighlight, t],
  )

  // 文件夹删除：确认式（后端成功才 remove）。失败只 toast、不动树，无需整树重拉；in-flight 期间锁节点。
  const deleteFolder = useCallback(
    async (id: number) => {
      const key = `folder-${id}`
      lock(key)
      try {
        const res = await authFetch(`${API_BASE}/api/catalog/nodes/${id}`, { method: "DELETE" })
        const json = (await res.json()) as ApiResponse<unknown>
        if (json.code === 0) {
          removeFolder(id)
          toast.success(t("catalog.toastFolderDeleted"))
        } else {
          toast.error(json.message || t("catalog.toastDeleteFailed"))
        }
      } catch {
        toast.error(t("catalog.toastDeleteFailed"))
      } finally {
        unlock(key)
      }
    },
    [lock, unlock, removeFolder, t],
  )

  // 统一提交：空名（建/改名/新建叶子）不关闭以引导输入，成功后关闭
  const submitDialog = async () => {
    const d = dialog
    if (d.mode === "folder") {
      if (!d.name.trim()) return
      await createFolder(d.parentId, d.name)
    } else if (d.mode === "folder-rename") {
      if (!d.name.trim()) return
      await renameFolder(d.id, d.name)
    } else if (d.mode === "folder-delete") {
      await deleteFolder(d.id)
    } else if (d.mode === "create-task") {
      if (!d.name.trim()) return
      await createLeaf("task", d.parentId, d.name, d.taskType)
    } else if (d.mode === "create-workflow") {
      if (!d.name.trim()) return
      await createLeaf("workflow", d.parentId, d.name, "SQL")
    } else if (d.mode === "rename") {
      if (!d.name.trim()) return
      await renameLeaf(d.kind, d.id, d.name)
    } else if (d.mode === "delete") {
      await deleteLeaf(d.kind, d.id)
    } else if (d.mode === "move") {
      await move({ kind: d.kind, id: d.id }, d.selectedId)
    } else {
      return
    }
    setDialog({ mode: "closed" })
  }

  // ── 渲染叶子（任务/工作流）──
  // 行包 ContextMenuTrigger：右键弹「重命名/删除」菜单，与 onClick(开 Tab)、draggable(移动/上画布) 正交。
  // 右侧小圆点：dirty=黄点（warning）> ONLINE=绿点（success）> DRAFT=灰点（muted）。
  const renderLeaf = (
    kind: "task" | "workflow",
    id: number,
    name: string,
    depth: number,
    status: "ONLINE" | "DRAFT",
    dirty: boolean,
  ) => {
    const key = `${kind}-${id}`
    const pending = pendingKeys.has(key)
    return (
      <motion.div
        key={key}
        layout
        {...itemMotion}
        className={highlightedKeys.has(key) ? highlightClass : ""}
      >
        <ContextMenu>
          <ContextMenuTrigger
            draggable={!pending}
            onClick={() => (kind === "task" ? onOpenTask?.(id, name) : onOpenWorkflow?.(id, name))}
            onDragStart={(e) => {
              e.dataTransfer.setData(MOVE_MIME, JSON.stringify({ kind, id } satisfies MovePayload))
              if (kind === "task" && draggableTasksToCanvas) {
                e.dataTransfer.setData(TASK_MIME, JSON.stringify({ id, name }))
              }
              e.dataTransfer.effectAllowed = "move"
            }}
            style={{ paddingLeft: depth * 22 + 4 }}
            className="flex cursor-grab items-center gap-1.5 rounded-md py-1.5 pr-2 text-sm hover:bg-accent"
          >
            <span className="size-4 shrink-0" aria-hidden />
            <HugeiconsIcon
              icon={kind === "task" ? Task01Icon : Share08Icon}
              className={`size-4 shrink-0 ${kind === "task" ? "text-primary" : "text-chart-2"}`}
            />
            <span className="truncate">{name}</span>
            <span
              className={`ml-auto size-1.5 shrink-0 rounded-full ${
                dirty ? "bg-warning" : status === "ONLINE" ? "bg-success" : "bg-muted-foreground/50"
              }`}
              aria-label={dirty ? t("catalog.statusDirty") : status === "ONLINE" ? t("catalog.statusOnline") : t("catalog.statusDraft")}
              title={dirty ? t("catalog.statusDirty") : status === "ONLINE" ? t("catalog.statusOnline") : t("catalog.statusDraft")}
            />
          </ContextMenuTrigger>
          <ContextMenuContent>
            <ContextMenuItem
              disabled={pending}
              onClick={() => {
                const st = useCatalogTreeStore.getState()
                const cur =
                  (kind === "task" ? st.tasks : st.workflows).find((x) => x.id === id)?.catalogNodeId ?? null
                setDialog({ mode: "move", kind, id, name, selectedId: cur })
              }}
            >
              <HugeiconsIcon icon={MoveToIcon} className="size-4" /> {t("catalog.moveTo")}
            </ContextMenuItem>
            <ContextMenuItem
              disabled={pending}
              onClick={() => setBackfill({ type: kind, id, name })}
            >
              <HugeiconsIcon icon={Calendar03Icon} className="size-4" /> {t("ops.backfillTrigger")}
            </ContextMenuItem>
            <ContextMenuItem
              disabled={pending}
              onClick={() => setDialog({ mode: "rename", kind, id, name })}
            >
              <HugeiconsIcon icon={PencilEdit01Icon} className="size-4" /> {t("catalog.rename")}
            </ContextMenuItem>
            <ContextMenuItem
              variant="destructive"
              disabled={pending}
              onClick={() => setDialog({ mode: "delete", kind, id, name })}
            >
              <HugeiconsIcon icon={Delete02Icon} className="size-4" /> {t("catalog.delete")}
            </ContextMenuItem>
          </ContextMenuContent>
        </ContextMenu>
      </motion.div>
    )
  }

  // ── 渲染文件夹（递归）──
  const renderFolder = (node: CatalogTreeNode, depth: number) => {
    if (searching && !(searchFolders?.has(node.id) ?? false)) return null
    const open = searching ? true : !!expanded[node.id]
    const childTasks = tasksByNode.get(node.id) ?? []
    const childWorkflows = workflowsByNode.get(node.id) ?? []
    const isDrop = dropTarget === node.id
    const nonEmpty = node.taskCount + node.workflowCount > 0 || (node.children ?? []).length > 0
    const key = `folder-${node.id}`
    const pending = pendingKeys.has(key)
    return (
      <motion.div
        key={key}
        layout
        initial={{ opacity: 0, height: 0, y: -6 }}
        animate={{ opacity: 1, height: "auto", y: 0 }}
        exit={{ opacity: 0, height: 0, y: -6 }}
        transition={itemMotion.transition}
        className={highlightedKeys.has(key) ? highlightClass : ""}
      >
        <ContextMenu>
          <ContextMenuTrigger
            onClick={() => toggleExpand(node.id)}
            onDragOver={enableMove && !pending ? (e) => { e.preventDefault(); setDropTarget(node.id) } : undefined}
            onDragLeave={enableMove && !pending ? () => setDropTarget((d) => (d === node.id ? null : d)) : undefined}
            onDrop={enableMove && !pending ? (e) => onFolderDrop(e, node.id) : undefined}
            style={{ paddingLeft: depth * 22 + 4 }}
            className={`flex cursor-pointer items-center gap-1.5 rounded-md py-1.5 pr-2 text-sm hover:bg-accent ${
              isDrop ? "bg-primary/10 ring-1 ring-primary" : ""
            }`}
          >
            <HugeiconsIcon
              icon={open ? ArrowDown01Icon : ArrowRight01Icon}
              className="size-4 shrink-0 text-muted-foreground"
            />
            <HugeiconsIcon
              icon={open ? FolderOpenIcon : Folder01Icon}
              className="size-4 shrink-0 text-amber-500"
            />
            <span className="truncate font-medium">{node.name}</span>
            <span className="ml-auto shrink-0 text-xs text-muted-foreground">
              {node.taskCount + node.workflowCount || ""}
            </span>
          </ContextMenuTrigger>
          <ContextMenuContent>
            <ContextMenuItem
              disabled={pending}
              onClick={() => setDialog({ mode: "folder", parentId: node.id, name: "" })}
            >
              <HugeiconsIcon icon={FolderAddIcon} className="size-4" /> {t("catalog.newSubFolder")}
            </ContextMenuItem>
            <ContextMenuItem
              disabled={pending}
              onClick={() => setDialog({ mode: "create-task", parentId: node.id, name: "", taskType: "SQL" })}
            >
              <HugeiconsIcon icon={Task01Icon} className="size-4 text-primary" /> {t("catalog.newTask")}
            </ContextMenuItem>
            <ContextMenuItem
              disabled={pending}
              onClick={() => setDialog({ mode: "create-workflow", parentId: node.id, name: "" })}
            >
              <HugeiconsIcon icon={Share08Icon} className="size-4 text-chart-2" /> {t("catalog.newWorkflow")}
            </ContextMenuItem>
            <ContextMenuSeparator />
            <ContextMenuItem
              disabled={pending}
              onClick={() => setDialog({ mode: "folder-rename", id: node.id, name: node.name })}
            >
              <HugeiconsIcon icon={PencilEdit01Icon} className="size-4" /> {t("catalog.rename")}
            </ContextMenuItem>
            <ContextMenuItem
              variant="destructive"
              disabled={nonEmpty || pending}
              title={nonEmpty ? t("catalog.mustEmptyFirst") : undefined}
              onClick={() => setDialog({ mode: "folder-delete", id: node.id, name: node.name })}
            >
              <HugeiconsIcon icon={Delete02Icon} className="size-4" /> {t("catalog.delete")}
            </ContextMenuItem>
          </ContextMenuContent>
        </ContextMenu>
        <AnimatePresence initial={false}>
          {open && (
            <motion.div
              key={`children-${node.id}`}
              initial={{ height: 0, opacity: 0 }}
              animate={{ height: "auto", opacity: 1 }}
              exit={{ height: 0, opacity: 0 }}
              transition={{ type: "spring", stiffness: 500, damping: 30, mass: 0.8 }}
              className="overflow-hidden"
            >
              {(node.children ?? []).map((c) => renderFolder(c, depth + 1))}
              {childTasks.map((t) => renderLeaf("task", t.id, t.name, depth + 1, t.status, !!taskDirtyState[t.id]))}
              {childWorkflows.map((w) => renderLeaf("workflow", w.id, w.name, depth + 1, w.status, false))}
            </motion.div>
          )}
        </AnimatePresence>
      </motion.div>
    )
  }

  // ── 未分类虚拟根 ──
  const renderUncategorized = () => {
    const childTasks = tasksByNode.get(UNCATEGORIZED) ?? []
    const childWorkflows = workflowsByNode.get(UNCATEGORIZED) ?? []
    if (childTasks.length === 0 && childWorkflows.length === 0) return null
    const open = searching ? true : (expanded[UNCATEGORIZED] ?? true)
    const isDrop = dropTarget === UNCATEGORIZED
    return (
      <div>
        <div
          onClick={() => toggleExpand(UNCATEGORIZED)}
          onDragOver={enableMove ? (e) => { e.preventDefault(); setDropTarget(UNCATEGORIZED) } : undefined}
          onDragLeave={enableMove ? () => setDropTarget((d) => (d === UNCATEGORIZED ? null : d)) : undefined}
          onDrop={enableMove ? (e) => onFolderDrop(e, null) : undefined}
          className={`flex cursor-pointer items-center gap-1.5 rounded-md px-1 py-1.5 pr-2 text-sm hover:bg-accent ${
            isDrop ? "bg-primary/10 ring-1 ring-primary" : ""
          }`}
        >
          <HugeiconsIcon
            icon={open ? ArrowDown01Icon : ArrowRight01Icon}
            className="size-4 shrink-0 text-muted-foreground"
          />
          <HugeiconsIcon icon={InboxIcon} className="size-4 shrink-0 text-muted-foreground" />
          <span className="truncate text-muted-foreground">{t("catalog.uncategorized")}</span>
          <span className="ml-auto shrink-0 text-xs text-muted-foreground">
            {childTasks.length + childWorkflows.length || ""}
          </span>
        </div>
        {open && (
          <div>
            {childTasks.map((t) => renderLeaf("task", t.id, t.name, 1, t.status, !!taskDirtyState[t.id]))}
            {childWorkflows.map((w) => renderLeaf("workflow", w.id, w.name, 1, w.status, false))}
          </div>
        )}
      </div>
    )
  }

  if (loading) {
    return <p className="p-3 text-sm text-muted-foreground">{t("catalog.loading")}</p>
  }

  return (
    <div className={`flex min-h-0 flex-col ${className ?? ""}`}>
      {/* 固定顶部：标题+新建/刷新 + 搜索 + 标签（不随树滚动，搜索框常驻——与血缘树一致）*/}
      <div className="flex shrink-0 flex-col gap-2 px-3 pt-3 pb-2">
        {/* 标题 + 新建（同一行，标题左、操作右）*/}
        <div className="flex items-center justify-between gap-2">
        <span className="text-sm font-medium">{t("catalog.title")}</span>
        <div className="flex shrink-0 items-center gap-0.5">
          <button
            type="button"
            onClick={() => setDialog({ mode: "folder", parentId: null, name: "" })}
            className="flex items-center gap-1 rounded-md px-1.5 py-1 text-xs text-muted-foreground hover:bg-accent hover:text-foreground"
            title={t("catalog.newRootFolder")}
          >
            <HugeiconsIcon icon={FolderAddIcon} className="size-4" /> {t("catalog.new")}
          </button>
          <button
            type="button"
            onClick={reload}
            disabled={loading}
            className="flex items-center rounded-md px-1.5 py-1 text-xs text-muted-foreground hover:bg-accent hover:text-foreground disabled:opacity-40"
            title={t("common.refresh")}
          >
            <HugeiconsIcon icon={RefreshIcon} className="size-4" />
          </button>
        </div>
      </div>

      {/* 本地搜索（D7）：子串过滤叶子，命中祖先自动展开，清空恢复 */}
      <Input
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        placeholder={t("catalog.searchPlaceholder")}
        className="h-8 text-xs"
      />

      {/* 标签过滤 chips */}
      {showTagFilter && tags.length > 0 && (
        <div className="flex flex-wrap items-center gap-1.5">
          <HugeiconsIcon icon={Tag01Icon} className="size-4 text-muted-foreground" />
          {tags.map((tg) => {
            const active = selectedTagIds.includes(tg.id)
            return (
              <button
                key={tg.id}
                type="button"
                onClick={() => toggleTag(tg.id)}
                className={`rounded-full border px-2 py-0.5 text-xs transition-colors ${
                  active
                    ? "border-primary bg-primary text-primary-foreground"
                    : "border-border text-muted-foreground hover:border-primary"
                }`}
                style={!active && tg.color ? { borderColor: tg.color, color: tg.color } : undefined}
              >
                {tg.name}
              </button>
            )
          })}
          {selectedTagIds.length > 0 && (
            <button
              type="button"
              onClick={clearTags}
              className="text-xs text-muted-foreground underline hover:text-foreground"
            >
              {t("catalog.clear")}
            </button>
          )}
        </div>
      )}

      </div>

      {/* 树体（可滚动区，固定搜索框在上）——容器右键 = 根级菜单：新建根文件夹/任务/工作流，均落根/未分类 */}
      <DwScroll className="min-h-0 flex-1" innerClassName="px-3 pb-3">
      <ContextMenu>
        <ContextMenuTrigger className="flex min-h-12 flex-col">
          <AnimatePresence initial={false}>
            {tree?.roots.map((r) => renderFolder(r, 0))}
          </AnimatePresence>
          {renderUncategorized()}
          {(!tree || tree.roots.length === 0) &&
            tasksByNode.size === 0 &&
            workflowsByNode.size === 0 && (
              searching ? (
                <p className="px-1 py-2 text-sm text-muted-foreground">{t("catalog.noMatches")}</p>
              ) : (
                <div className="flex flex-col gap-0.5 px-1 py-2 text-sm text-muted-foreground">
                  <span>{t("catalog.empty")}</span>
                  <span className="text-xs text-muted-foreground/70">{t("catalog.emptyHint")}</span>
                </div>
              )
            )}
        </ContextMenuTrigger>
        <ContextMenuContent>
          <ContextMenuItem onClick={() => setDialog({ mode: "folder", parentId: null, name: "" })}>
            <HugeiconsIcon icon={FolderAddIcon} className="size-4" /> {t("catalog.newRootFolder")}
          </ContextMenuItem>
          <ContextMenuItem
            onClick={() => setDialog({ mode: "create-task", parentId: null, name: "", taskType: "SQL" })}
          >
            <HugeiconsIcon icon={Task01Icon} className="size-4 text-primary" /> {t("catalog.newTask")}
          </ContextMenuItem>
          <ContextMenuItem
            onClick={() => setDialog({ mode: "create-workflow", parentId: null, name: "" })}
          >
            <HugeiconsIcon icon={Share08Icon} className="size-4 text-chart-2" /> {t("catalog.newWorkflow")}
          </ContextMenuItem>
        </ContextMenuContent>
      </ContextMenu>
      </DwScroll>

      {/* 就地补数据弹窗：右键叶子触发,预填目标 */}
      <BackfillDialog
        open={!!backfill}
        onOpenChange={(open) => {
          if (!open) setBackfill(null)
        }}
        initialTargetType={backfill?.type ?? "task"}
        initialTargetId={backfill?.id ?? ""}
        initialTargetName={backfill?.name}
      />

      {/* 统一 Dialog：建文件夹 / 文件夹改名删除 / 在此新建任务工作流 / 叶子改名删除 */}
      <Dialog
        open={dialog.mode !== "closed"}
        onOpenChange={(open) => {
          if (!open) closeDialog()
        }}
      >
        <DialogContent className="max-w-sm">
          <DialogHeader>
            <DialogTitle>
              {dialog.mode === "folder" &&
                (dialog.parentId == null ? t("catalog.dialogNewRootFolder") : t("catalog.dialogNewSubFolder"))}
              {dialog.mode === "folder-rename" && t("catalog.dialogRenameFolder")}
              {dialog.mode === "folder-delete" && t("catalog.dialogDeleteFolder")}
              {dialog.mode === "create-task" && t("catalog.dialogNewTask")}
              {dialog.mode === "create-workflow" && t("catalog.dialogNewWorkflow")}
              {dialog.mode === "move" && t("catalog.dialogMoveTo", { name: dialog.name })}
              {dialog.mode === "rename" &&
                (dialog.kind === "task" ? t("catalog.dialogRenameTask") : t("catalog.dialogRenameWorkflow"))}
              {dialog.mode === "delete" &&
                (dialog.kind === "task" ? t("catalog.dialogDeleteTask") : t("catalog.dialogDeleteWorkflow"))}
            </DialogTitle>
            {dialog.mode === "delete" && (
              <DialogDescription>
                {t("catalog.confirmDeleteLeaf", { name: dialog.name })}
              </DialogDescription>
            )}
            {dialog.mode === "folder-delete" && (
              <DialogDescription>
                {t("catalog.confirmDeleteFolder", { name: dialog.name })}
              </DialogDescription>
            )}
          </DialogHeader>
          {/* 名称输入：除删除态外均需 */}
          {dialog.mode !== "delete" &&
            dialog.mode !== "folder-delete" &&
            dialog.mode !== "move" &&
            dialog.mode !== "closed" && (
              <Input
                autoFocus
                value={dialog.name}
                placeholder={t("catalog.name")}
                onChange={(e) =>
                  setDialog((d) => (d.mode !== "closed" ? { ...d, name: e.target.value } : d))
                }
                onKeyDown={(e) => {
                  if (e.key === "Enter") {
                    e.preventDefault()
                    submitDialog()
                  }
                }}
              />
            )}
          {/* 移动归属：文件夹选择器 */}
          {dialog.mode === "move" && (
            <MoveFolderPicker
              selectedId={dialog.selectedId}
              onSelect={(id) => setDialog((d) => (d.mode === "move" ? { ...d, selectedId: id } : d))}
            />
          )}
          {/* 新建任务额外选类型 */}
          {dialog.mode === "create-task" && (
            <DropdownSelect
              value={dialog.taskType}
              onChange={(v) =>
                setDialog((d) =>
                  d.mode === "create-task" ? { ...d, taskType: v as "SQL" | "SHELL" } : d,
                )
              }
              options={[
                { value: "SQL", label: t("nodeDetail.taskTypeSQL") },
                { value: "SHELL", label: t("nodeDetail.taskTypeShell") },
              ]}
              disableClear
            />
          )}
          <DialogFooter>
            <DialogClose render={<Button variant="ghost" />}>{t("catalog.cancel")}</DialogClose>
            {dialog.mode === "delete" || dialog.mode === "folder-delete" ? (
              <Button variant="destructive" onClick={submitDialog}>
                {t("catalog.delete")}
              </Button>
            ) : (
              <Button onClick={submitDialog}>
                {dialog.mode === "rename" || dialog.mode === "folder-rename"
                  ? t("catalog.save")
                  : dialog.mode === "move"
                    ? t("catalog.moveAction")
                    : t("catalog.create")}
              </Button>
            )}
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}
