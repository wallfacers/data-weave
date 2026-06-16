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

const MOVE_MIME = "application/dw-catalog-move"
const TASK_MIME = "application/dw-task"
const UNCATEGORIZED = -1

interface MovePayload {
  kind: "task" | "workflow"
  id: number
}

/** 统一 Dialog 状态：建文件夹 / 重命名叶子 / 删除叶子。 */
type DialogState =
  | { mode: "closed" }
  | { mode: "folder"; parentId: number | null; name: string }
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
      for (const child of node.children) walk(child, c)
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

  // ── 移动归属（树内拖放）──
  const move = useCallback(
    async (payload: MovePayload, targetNodeId: number | null) => {
      const base = payload.kind === "task" ? "tasks" : "workflows"
      try {
        const res = await authFetch(`${API_BASE}/api/${base}/${payload.id}/catalog`, {
          method: "PATCH",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ catalogNodeId: targetNodeId }),
        })
        const json = (await res.json()) as ApiResponse<unknown>
        if (json.code === 0) {
          toast.success("已移动归属")
          reload()
        } else {
          toast.error(json.message || "移动失败")
        }
      } catch {
        toast.error("移动失败")
      }
    },
    [reload],
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
        if (json.code === 0) {
          if (parentId != null) expand(parentId)
          reload()
        } else {
          toast.error(json.message || "创建失败")
        }
      } catch {
        toast.error("创建失败")
      }
    },
    [projectId, expand, reload],
  )

  // 重命名叶子：PUT /api/{tasks|workflows}/{id}（sparse body 仅 name）。
  // 注意：后端 TaskService.update/softDelete 仅允许 DRAFT，已发布任务改名/删会被拒（领域约束，非本组件 bug）。
  const renameLeaf = useCallback(
    async (kind: "task" | "workflow", id: number, name: string) => {
      if (!name.trim()) return
      const base = kind === "task" ? "tasks" : "workflows"
      try {
        const res = await authFetch(`${API_BASE}/api/${base}/${id}`, {
          method: "PUT",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ name: name.trim() }),
        })
        const json = (await res.json()) as ApiResponse<unknown>
        if (json.code === 0) {
          toast.success("已重命名")
          reload()
        } else {
          toast.error(json.message || "重命名失败")
        }
      } catch {
        toast.error("重命名失败")
      }
    },
    [reload],
  )

  const deleteLeaf = useCallback(
    async (kind: "task" | "workflow", id: number) => {
      const base = kind === "task" ? "tasks" : "workflows"
      try {
        const res = await authFetch(`${API_BASE}/api/${base}/${id}`, { method: "DELETE" })
        const json = (await res.json()) as ApiResponse<unknown>
        if (json.code === 0) {
          toast.success("已删除")
          reload()
        } else {
          toast.error(json.message || "删除失败")
        }
      } catch {
        toast.error("删除失败")
      }
    },
    [reload],
  )

  // 统一提交：空名（建文件夹/重命名）不关闭以引导输入，成功后关闭
  const submitDialog = async () => {
    const d = dialog
    if (d.mode === "folder") {
      if (!d.name.trim()) return
      await createFolder(d.parentId, d.name)
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
  const renderLeaf = (kind: "task" | "workflow", id: number, name: string, depth: number) => (
    <div
      key={`${kind}-${id}`}
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
      className="group/leaf flex cursor-grab items-center gap-1.5 rounded-md py-1.5 pr-2 text-sm hover:bg-accent"
    >
      {/* chevron 占位：叶子无展开三角，补齐宽度让图标与同级子文件夹对齐、比父级缩进一级 */}
      <span className="size-4 shrink-0" aria-hidden />
      <HugeiconsIcon
        icon={kind === "task" ? Task01Icon : WorkflowSquare01Icon}
        className={`size-4 shrink-0 ${kind === "task" ? "text-primary" : "text-chart-2"}`}
      />
      <span className="truncate">{name}</span>
      {/* 行内操作（hover 显） */}
      <span className="ml-auto flex shrink-0 items-center gap-0.5 opacity-0 transition-opacity group-hover/leaf:opacity-100">
        <button
          type="button"
          title="重命名"
          className="rounded p-0.5 text-muted-foreground hover:bg-accent hover:text-foreground"
          onClick={(e) => {
            e.stopPropagation()
            setDialog({ mode: "rename", kind, id, name })
          }}
        >
          <HugeiconsIcon icon={PencilEdit01Icon} className="size-3.5" />
        </button>
        <button
          type="button"
          title="删除"
          className="rounded p-0.5 text-muted-foreground hover:bg-accent hover:text-destructive"
          onClick={(e) => {
            e.stopPropagation()
            setDialog({ mode: "delete", kind, id, name })
          }}
        >
          <HugeiconsIcon icon={Delete02Icon} className="size-3.5" />
        </button>
      </span>
    </div>
  )

  // ── 渲染文件夹（递归）──
  const renderFolder = (node: CatalogTreeNode, depth: number) => {
    // 搜索态：仅渲染命中叶子的祖先文件夹（searchFolders），否则按原结构
    if (searching && !(searchFolders?.has(node.id) ?? false)) return null
    const open = searching ? true : !!expanded[node.id]
    const childTasks = tasksByNode.get(node.id) ?? []
    const childWorkflows = workflowsByNode.get(node.id) ?? []
    const isDrop = dropTarget === node.id
    return (
      <div key={`folder-${node.id}`}>
        <div
          onClick={() => toggleExpand(node.id)}
          onDragOver={enableMove ? (e) => { e.preventDefault(); setDropTarget(node.id) } : undefined}
          onDragLeave={enableMove ? () => setDropTarget((d) => (d === node.id ? null : d)) : undefined}
          onDrop={enableMove ? (e) => onFolderDrop(e, node.id) : undefined}
          style={{ paddingLeft: depth * 22 + 4 }}
          className={`group flex cursor-pointer items-center gap-1.5 rounded-md py-1.5 pr-2 text-sm hover:bg-accent ${
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
          {!searching && (
            <button
              type="button"
              onClick={(e) => { e.stopPropagation(); setDialog({ mode: "folder", parentId: node.id, name: "" }) }}
              className="hidden shrink-0 text-muted-foreground hover:text-foreground group-hover:block"
              title="新建子文件夹"
            >
              <HugeiconsIcon icon={FolderAddIcon} className="size-4" />
            </button>
          )}
        </div>
        {open && (
          <div>
            {node.children.map((c) => renderFolder(c, depth + 1))}
            {childTasks.map((t) => renderLeaf("task", t.id, t.name, depth + 1))}
            {childWorkflows.map((w) => renderLeaf("workflow", w.id, w.name, depth + 1))}
          </div>
        )}
      </div>
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

      {/* 树体 */}
      <div className="flex flex-col">
        {tree?.roots.map((r) => renderFolder(r, 0))}
        {renderUncategorized()}
        {(!tree || tree.roots.length === 0) &&
          tasksByNode.size === 0 &&
          workflowsByNode.size === 0 && (
            <p className="px-1 py-2 text-sm text-muted-foreground">
              {searching ? "未匹配到任务/工作流" : "暂无类目，点「新建」建文件夹"}
            </p>
          )}
      </div>

      {/* 统一 Dialog（建文件夹 / 重命名 / 删除） */}
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
          </DialogHeader>
          {(dialog.mode === "folder" || dialog.mode === "rename") && (
            <Input
              autoFocus
              value={dialog.name}
              placeholder="名称"
              onChange={(e) =>
                setDialog((d) =>
                  d.mode === "folder" || d.mode === "rename"
                    ? { ...d, name: e.target.value }
                    : d,
                )
              }
              onKeyDown={(e) => {
                if (e.key === "Enter") {
                  e.preventDefault()
                  submitDialog()
                }
              }}
            />
          )}
          <DialogFooter>
            <DialogClose render={<Button variant="ghost" />}>取消</DialogClose>
            {dialog.mode === "delete" ? (
              <Button variant="destructive" onClick={submitDialog}>
                删除
              </Button>
            ) : (
              <Button onClick={submitDialog}>
                {dialog.mode === "rename" ? "保存" : "创建"}
              </Button>
            )}
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}
