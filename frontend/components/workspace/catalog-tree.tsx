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
import { useCallback, useEffect, useMemo, useState } from "react"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  ArrowDown01Icon,
  ArrowRight01Icon,
  Delete02Icon,
  Folder01Icon,
  FolderAddIcon,
  FolderOpenIcon,
  InboxIcon,
  PencilEdit01Icon,
  Tag01Icon,
  Task01Icon,
  WorkflowSquare01Icon,
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
import {
  ContextMenu,
  ContextMenuTrigger,
  ContextMenuContent,
  ContextMenuItem,
  ContextMenuSeparator,
} from "@/components/ui/context-menu"

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

// ── 乐观树操作：直接改本地 state，不触发整树 reload ──

/** 把新文件夹节点插入树的正确位置（parentId=null → roots，否则递归找父）。 */
function insertFolderIntoTree(
  roots: CatalogTreeNode[],
  parentId: number | null,
  newNode: CatalogTreeNode,
): CatalogTreeNode[] {
  // 后端返回的新节点可能不含 children 字段，补上空数组
  const safeNode = { ...newNode, children: newNode.children ?? [] }
  if (parentId == null) return [...roots, safeNode]
  return roots.map((r) =>
    r.id === parentId
      ? { ...r, children: [...r.children, safeNode] }
      : { ...r, children: insertFolderIntoTree(r.children, parentId, newNode) },
  )
}

/** 从树中移除指定文件夹（递归）。 */
function removeFolderFromTree(roots: CatalogTreeNode[], folderId: number): CatalogTreeNode[] {
  return roots
    .filter((r) => r.id !== folderId)
    .map((r) => ({ ...r, children: removeFolderFromTree(r.children ?? [], folderId) }))
}

/** 更新树中指定文件夹的名称。 */
function renameFolderInTree(roots: CatalogTreeNode[], folderId: number, name: string): CatalogTreeNode[] {
  return roots.map((r) =>
    r.id === folderId
      ? { ...r, name }
      : { ...r, children: renameFolderInTree(r.children ?? [], folderId, name) },
  )
}

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
  /** 外部触发刷新（变更后 bump）。 */
  reloadKey?: number
  className?: string
}

export function CatalogTree({
  projectId = 1,
  draggableTasksToCanvas = false,
  enableMove = true,
  showTagFilter = true,
  onOpenTask,
  onOpenWorkflow,
  reloadKey,
  className,
}: CatalogTreeProps) {
  const { expanded, toggleExpand, expand, selectedTagIds, toggleTag, clearTags } =
    useCatalogTreeStore()

  const [tree, setTree] = useState<CatalogTreeData | null>(null)
  const [tasks, setTasks] = useState<TaskDef[]>([])
  const [workflows, setWorkflows] = useState<WorkflowDef[]>([])
  const [tags, setTags] = useState<Tag[]>([])
  const [visibleTaskIds, setVisibleTaskIds] = useState<Set<number> | null>(null)
  const [visibleWorkflowIds, setVisibleWorkflowIds] = useState<Set<number> | null>(null)
  const [loading, setLoading] = useState(true)
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

  // ── 本地搜索（D7）：子串过滤叶子 + 命中祖先自动展开；清空恢复 store 原展开态 ──
  const [query, setQuery] = useState("")
  const q = query.trim().toLowerCase()
  const searching = q !== ""

  // ── 统一 Dialog ──
  const [dialog, setDialog] = useState<DialogState>({ mode: "closed" })
  const closeDialog = useCallback(() => setDialog({ mode: "closed" }), [])

  // ── 基础数据：树 + 任务 + 工作流 + 标签 ──
  useEffect(() => {
    let alive = true
    setLoading(true)
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
        setTree(tr)
        setTasks(tks)
        setWorkflows(wfs)
        setTags(tgs)
      })
      .finally(() => alive && setLoading(false))
    return () => {
      alive = false
    }
  }, [projectId, reloadKey, tick])

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
  const move = useCallback(
    async (payload: MovePayload, targetNodeId: number | null) => {
      const base = payload.kind === "task" ? "tasks" : "workflows"
      // 乐观更新：直接改本地 state
      if (payload.kind === "task") {
        setTasks((prev) => prev.map((i) => i.id === payload.id ? { ...i, catalogNodeId: targetNodeId } : i))
      } else {
        setWorkflows((prev) => prev.map((i) => i.id === payload.id ? { ...i, catalogNodeId: targetNodeId } : i))
      }
      if (targetNodeId != null) expand(targetNodeId)
      flashHighlight([`${payload.kind}-${payload.id}`])
      try {
        const res = await authFetch(`${API_BASE}/api/${base}/${payload.id}/catalog`, {
          method: "PATCH",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ catalogNodeId: targetNodeId }),
        })
        const json = (await res.json()) as ApiResponse<unknown>
        if (json.code === 0) {
          toast.success("已移动归属")
        } else {
          toast.error(json.message || "移动失败")
          reload() // 回滚：全量重拉
        }
      } catch {
        toast.error("移动失败")
        reload()
      }
    },
    [expand, reload, flashHighlight],
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
          setTree((prev) =>
            prev ? { ...prev, roots: insertFolderIntoTree(prev.roots, parentId, newNode) } : prev,
          )
          if (parentId != null) expand(parentId)
          flashHighlight([`folder-${newNode.id}`])
          toast.success("已创建文件夹")
        } else {
          toast.error(json.message || "创建失败")
        }
      } catch {
        toast.error("创建失败")
      }
    },
    [projectId, expand, flashHighlight],
  )

  // 重命名叶子：乐观更新 name，失败回滚全量重拉。
  const renameLeaf = useCallback(
    async (kind: "task" | "workflow", id: number, name: string) => {
      if (!name.trim()) return
      const base = kind === "task" ? "tasks" : "workflows"
      // 乐观更新
      if (kind === "task") {
        setTasks((prev) => prev.map((i) => (i.id === id ? { ...i, name: name.trim() } : i)))
      } else {
        setWorkflows((prev) => prev.map((i) => (i.id === id ? { ...i, name: name.trim() } : i)))
      }
      flashHighlight([`${kind}-${id}`])
      try {
        const res = await authFetch(`${API_BASE}/api/${base}/${id}`, {
          method: "PUT",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ name: name.trim() }),
        })
        const json = (await res.json()) as ApiResponse<unknown>
        if (json.code === 0) {
          toast.success("已重命名")
        } else {
          toast.error(json.message || "重命名失败")
          reload()
        }
      } catch {
        toast.error("重命名失败")
        reload()
      }
    },
    [reload, flashHighlight],
  )

  const deleteLeaf = useCallback(
    async (kind: "task" | "workflow", id: number) => {
      const base = kind === "task" ? "tasks" : "workflows"
      // 乐观删除（motion exit 动画会先播放）
      if (kind === "task") {
        setTasks((prev) => prev.filter((i) => i.id !== id))
      } else {
        setWorkflows((prev) => prev.filter((i) => i.id !== id))
      }
      try {
        const res = await authFetch(`${API_BASE}/api/${base}/${id}`, { method: "DELETE" })
        const json = (await res.json()) as ApiResponse<unknown>
        if (json.code === 0) {
          toast.success("已删除")
        } else {
          toast.error(json.message || "删除失败")
          reload()
        }
      } catch {
        toast.error("删除失败")
        reload()
      }
    },
    [reload],
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
            setTasks((prev) => [...prev, newItem as TaskDef])
          } else {
            setWorkflows((prev) => [...prev, newItem as WorkflowDef])
          }
          if (parentId != null) expand(parentId)
          flashHighlight([`${kind}-${(newItem as TaskDef).id}`])
          toast.success(kind === "task" ? "已创建任务草稿" : "已创建工作流草稿")
          if (kind === "task") onOpenTask?.(newItem.id, newItem.name)
          else onOpenWorkflow?.(newItem.id, newItem.name)
        } else {
          toast.error(json.message || "创建失败")
        }
      } catch {
        toast.error("创建失败")
      }
    },
    [onOpenTask, onOpenWorkflow, expand, flashHighlight],
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
          setTree((prev) =>
            prev ? { ...prev, roots: renameFolderInTree(prev.roots, id, name.trim()) } : prev,
          )
          toast.success("已重命名")
          flashHighlight([`folder-${id}`])
        } else {
          toast.error(json.message || "重命名失败")
        }
      } catch {
        toast.error("重命名失败")
      }
    },
    [flashHighlight],
  )

  // 文件夹删除：乐观从树中移除（motion exit 动画先播放）。
  const deleteFolder = useCallback(
    async (id: number) => {
      try {
        const res = await authFetch(`${API_BASE}/api/catalog/nodes/${id}`, { method: "DELETE" })
        const json = (await res.json()) as ApiResponse<unknown>
        if (json.code === 0) {
          setTree((prev) =>
            prev ? { ...prev, roots: removeFolderFromTree(prev.roots, id) } : prev,
          )
          toast.success("已删除文件夹")
        } else {
          toast.error(json.message || "删除失败")
          reload()
        }
      } catch {
        toast.error("删除失败")
        reload()
      }
    },
    [reload],
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
    } else {
      return
    }
    setDialog({ mode: "closed" })
  }

  // ── 渲染叶子（任务/工作流）──
  // 行包 ContextMenuTrigger：右键弹「重命名/删除」菜单，与 onClick(开 Tab)、draggable(移动/上画布) 正交。
  const renderLeaf = (kind: "task" | "workflow", id: number, name: string, depth: number) => {
    const key = `${kind}-${id}`
    return (
      <motion.div
        key={key}
        layout
        {...itemMotion}
        className={highlightedKeys.has(key) ? highlightClass : ""}
      >
        <ContextMenu>
          <ContextMenuTrigger
            draggable
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
              icon={kind === "task" ? Task01Icon : WorkflowSquare01Icon}
              className={`size-4 shrink-0 ${kind === "task" ? "text-primary" : "text-chart-2"}`}
            />
            <span className="truncate">{name}</span>
          </ContextMenuTrigger>
          <ContextMenuContent>
            <ContextMenuItem onClick={() => setDialog({ mode: "rename", kind, id, name })}>
              <HugeiconsIcon icon={PencilEdit01Icon} className="size-4" /> 重命名
            </ContextMenuItem>
            <ContextMenuItem
              variant="destructive"
              onClick={() => setDialog({ mode: "delete", kind, id, name })}
            >
              <HugeiconsIcon icon={Delete02Icon} className="size-4" /> 删除
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
            onDragOver={enableMove ? (e) => { e.preventDefault(); setDropTarget(node.id) } : undefined}
            onDragLeave={enableMove ? () => setDropTarget((d) => (d === node.id ? null : d)) : undefined}
            onDrop={enableMove ? (e) => onFolderDrop(e, node.id) : undefined}
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
            <ContextMenuItem onClick={() => setDialog({ mode: "folder", parentId: node.id, name: "" })}>
              <HugeiconsIcon icon={FolderAddIcon} className="size-4" /> 新建子文件夹
            </ContextMenuItem>
            <ContextMenuItem
              onClick={() => setDialog({ mode: "create-task", parentId: node.id, name: "", taskType: "SQL" })}
            >
              <HugeiconsIcon icon={Task01Icon} className="size-4 text-primary" /> 新建任务
            </ContextMenuItem>
            <ContextMenuItem
              onClick={() => setDialog({ mode: "create-workflow", parentId: node.id, name: "" })}
            >
              <HugeiconsIcon icon={WorkflowSquare01Icon} className="size-4 text-chart-2" /> 新建工作流
            </ContextMenuItem>
            <ContextMenuSeparator />
            <ContextMenuItem onClick={() => setDialog({ mode: "folder-rename", id: node.id, name: node.name })}>
              <HugeiconsIcon icon={PencilEdit01Icon} className="size-4" /> 重命名
            </ContextMenuItem>
            <ContextMenuItem
              variant="destructive"
              disabled={nonEmpty}
              title={nonEmpty ? "请先清空或移走子项" : undefined}
              onClick={() => setDialog({ mode: "folder-delete", id: node.id, name: node.name })}
            >
              <HugeiconsIcon icon={Delete02Icon} className="size-4" /> 删除
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
              {childTasks.map((t) => renderLeaf("task", t.id, t.name, depth + 1))}
              {childWorkflows.map((w) => renderLeaf("workflow", w.id, w.name, depth + 1))}
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
          <span className="truncate text-muted-foreground">未分类</span>
          <span className="ml-auto shrink-0 text-xs text-muted-foreground">
            {childTasks.length + childWorkflows.length || ""}
          </span>
        </div>
        {open && (
          <div>
            {childTasks.map((t) => renderLeaf("task", t.id, t.name, 1))}
            {childWorkflows.map((w) => renderLeaf("workflow", w.id, w.name, 1))}
          </div>
        )}
      </div>
    )
  }

  if (loading) {
    return <p className="p-3 text-sm text-muted-foreground">加载类目…</p>
  }

  return (
    <div className={`flex flex-col gap-2 ${className ?? ""}`}>
      {/* 顶部：标题 + 新建（同一行，标题左、操作右）*/}
      <div className="flex items-center justify-between gap-2">
        <span className="text-sm font-medium">类目</span>
        <button
          type="button"
          onClick={() => setDialog({ mode: "folder", parentId: null, name: "" })}
          className="flex shrink-0 items-center gap-1 rounded-md px-1.5 py-1 text-xs text-muted-foreground hover:bg-accent hover:text-foreground"
          title="新建根文件夹"
        >
          <HugeiconsIcon icon={FolderAddIcon} className="size-4" /> 新建
        </button>
      </div>

      {/* 本地搜索（D7）：子串过滤叶子，命中祖先自动展开，清空恢复 */}
      <Input
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        placeholder="搜索任务/工作流"
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
              清除
            </button>
          )}
        </div>
      )}

      {/* 树体（容器右键 = 根级菜单：新建根文件夹 / 新建任务 / 新建工作流，均落根/未分类）*/}
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
                <p className="px-1 py-2 text-sm text-muted-foreground">未匹配到任务/工作流</p>
              ) : (
                <div className="flex flex-col gap-0.5 px-1 py-2 text-sm text-muted-foreground">
                  <span>暂无类目</span>
                  <span className="text-xs text-muted-foreground/70">右键或点「新建」建文件夹</span>
                </div>
              )
            )}
        </ContextMenuTrigger>
        <ContextMenuContent>
          <ContextMenuItem onClick={() => setDialog({ mode: "folder", parentId: null, name: "" })}>
            <HugeiconsIcon icon={FolderAddIcon} className="size-4" /> 新建根文件夹
          </ContextMenuItem>
          <ContextMenuItem
            onClick={() => setDialog({ mode: "create-task", parentId: null, name: "", taskType: "SQL" })}
          >
            <HugeiconsIcon icon={Task01Icon} className="size-4 text-primary" /> 新建任务
          </ContextMenuItem>
          <ContextMenuItem
            onClick={() => setDialog({ mode: "create-workflow", parentId: null, name: "" })}
          >
            <HugeiconsIcon icon={WorkflowSquare01Icon} className="size-4 text-chart-2" /> 新建工作流
          </ContextMenuItem>
        </ContextMenuContent>
      </ContextMenu>

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
                (dialog.parentId == null ? "新建根文件夹" : "新建子文件夹")}
              {dialog.mode === "folder-rename" && "重命名文件夹"}
              {dialog.mode === "folder-delete" && "删除文件夹"}
              {dialog.mode === "create-task" && "新建任务"}
              {dialog.mode === "create-workflow" && "新建工作流"}
              {dialog.mode === "rename" &&
                `重命名${dialog.kind === "task" ? "任务" : "工作流"}`}
              {dialog.mode === "delete" &&
                `删除${dialog.kind === "task" ? "任务" : "工作流"}`}
            </DialogTitle>
            {dialog.mode === "delete" && (
              <DialogDescription>
                确定删除「{dialog.name}」？此操作为软删除。
              </DialogDescription>
            )}
            {dialog.mode === "folder-delete" && (
              <DialogDescription>
                确定删除文件夹「{dialog.name}」？
              </DialogDescription>
            )}
          </DialogHeader>
          {/* 名称输入：除删除态外均需 */}
          {dialog.mode !== "delete" &&
            dialog.mode !== "folder-delete" &&
            dialog.mode !== "closed" && (
              <Input
                autoFocus
                value={dialog.name}
                placeholder="名称"
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
                { value: "SQL", label: "SQL" },
                { value: "SHELL", label: "SHELL" },
              ]}
            />
          )}
          <DialogFooter>
            <DialogClose render={<Button variant="ghost" />}>取消</DialogClose>
            {dialog.mode === "delete" || dialog.mode === "folder-delete" ? (
              <Button variant="destructive" onClick={submitDialog}>
                删除
              </Button>
            ) : (
              <Button onClick={submitDialog}>
                {dialog.mode === "rename" || dialog.mode === "folder-rename" ? "保存" : "创建"}
              </Button>
            )}
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}
