"use client"

/**
 * 可复用类目树 <CatalogTree>（task-workflow-catalog）。
 *
 * 文件夹树（唯一归属）+ 标签横切过滤，任务与工作流统一混挂。受控展开态由
 * useCatalogTreeStore 持有。首发落点为工作流画布左侧拖拽面板。
 *
 * 两种拖拽语义（以 drop target 区分，见 D7）：
 *   ① 树内把叶子拖入文件夹 → 移动归属（PATCH /api/{tasks|workflows}/{id}/catalog）。
 *      载荷类型 application/dw-catalog-move，文件夹行作为 drop target 读取。
 *   ② 任务叶子拖到画布（ReactFlow pane）→ 建 DAG 节点。
 *      载荷类型 application/dw-task，仅当 draggableTasksToCanvas 时附加；画布 onDrop 读取。
 * 一个任务叶子可同时携带两种载荷，由落点决定生效哪一种——互不串味。
 */
import { useCallback, useEffect, useMemo, useState } from "react"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  Folder01Icon,
  FolderOpenIcon,
  FolderAddIcon,
  ArrowRight01Icon,
  ArrowDown01Icon,
  DatabaseIcon,
  WorkflowSquare01Icon,
  Tag01Icon,
  InboxIcon,
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

const MOVE_MIME = "application/dw-catalog-move"
const TASK_MIME = "application/dw-task"
const UNCATEGORIZED = -1

interface MovePayload {
  kind: "task" | "workflow"
  id: number
}

export interface CatalogTreeProps {
  /** 项目 ID（默认 1）。 */
  projectId?: number
  /** 任务叶子是否额外携带 application/dw-task 载荷（供画布建节点）。 */
  draggableTasksToCanvas?: boolean
  /** 是否允许树内拖动叶子改归属（文件夹作 drop target）。 */
  enableMove?: boolean
  /** 是否显示顶部标签过滤 chips。 */
  showTagFilter?: boolean
  /** 外部触发刷新（变更后 bump）。 */
  reloadKey?: number
  className?: string
}

export function CatalogTree({
  projectId = 1,
  draggableTasksToCanvas = false,
  enableMove = true,
  showTagFilter = true,
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

  // ── 按归属分组（应用标签可见集过滤）──
  const tasksByNode = useMemo(() => {
    const map = new Map<number, TaskDef[]>()
    for (const t of tasks) {
      if (visibleTaskIds && !visibleTaskIds.has(t.id)) continue
      const key = t.catalogNodeId ?? UNCATEGORIZED
      ;(map.get(key) ?? map.set(key, []).get(key)!).push(t)
    }
    return map
  }, [tasks, visibleTaskIds])

  const workflowsByNode = useMemo(() => {
    const map = new Map<number, WorkflowDef[]>()
    for (const w of workflows) {
      if (visibleWorkflowIds && !visibleWorkflowIds.has(w.id)) continue
      const key = w.catalogNodeId ?? UNCATEGORIZED
      ;(map.get(key) ?? map.set(key, []).get(key)!).push(w)
    }
    return map
  }, [workflows, visibleWorkflowIds])

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

  // ── 新建文件夹（phase-1：prompt）──
  const createFolder = useCallback(
    async (parentId: number | null) => {
      const name = window.prompt("新建文件夹名称")
      if (!name || !name.trim()) return
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

  // ── 渲染叶子（任务/工作流）──
  const renderLeaf = (kind: "task" | "workflow", id: number, name: string, depth: number) => (
    <div
      key={`${kind}-${id}`}
      draggable
      onDragStart={(e) => {
        e.dataTransfer.setData(MOVE_MIME, JSON.stringify({ kind, id } satisfies MovePayload))
        if (kind === "task" && draggableTasksToCanvas) {
          e.dataTransfer.setData(TASK_MIME, JSON.stringify({ id, name }))
        }
        e.dataTransfer.effectAllowed = "move"
      }}
      style={{ paddingLeft: depth * 14 + 8 }}
      className="flex cursor-grab items-center gap-2 rounded-md py-1.5 pr-2 text-xs hover:bg-accent"
    >
      <HugeiconsIcon
        icon={kind === "task" ? DatabaseIcon : WorkflowSquare01Icon}
        className={`size-3.5 shrink-0 ${kind === "task" ? "text-primary" : "text-chart-2"}`}
      />
      <span className="truncate">{name}</span>
    </div>
  )

  // ── 渲染文件夹（递归）──
  const renderFolder = (node: CatalogTreeNode, depth: number) => {
    const open = !!expanded[node.id]
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
          style={{ paddingLeft: depth * 14 + 4 }}
          className={`group flex cursor-pointer items-center gap-1.5 rounded-md py-1.5 pr-2 text-xs hover:bg-accent ${
            isDrop ? "bg-primary/10 ring-1 ring-primary" : ""
          }`}
        >
          <HugeiconsIcon
            icon={open ? ArrowDown01Icon : ArrowRight01Icon}
            className="size-3.5 shrink-0 text-muted-foreground"
          />
          <HugeiconsIcon
            icon={open ? FolderOpenIcon : Folder01Icon}
            className="size-3.5 shrink-0 text-amber-500"
          />
          <span className="truncate font-medium">{node.name}</span>
          <span className="ml-auto shrink-0 text-[10px] text-muted-foreground">
            {node.taskCount + node.workflowCount || ""}
          </span>
          <button
            type="button"
            onClick={(e) => { e.stopPropagation(); createFolder(node.id) }}
            className="hidden shrink-0 text-muted-foreground hover:text-foreground group-hover:block"
            title="新建子文件夹"
          >
            <HugeiconsIcon icon={FolderAddIcon} className="size-3.5" />
          </button>
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
    const open = expanded[UNCATEGORIZED] ?? true
    const childTasks = tasksByNode.get(UNCATEGORIZED) ?? []
    const childWorkflows = workflowsByNode.get(UNCATEGORIZED) ?? []
    if (childTasks.length === 0 && childWorkflows.length === 0) return null
    const isDrop = dropTarget === UNCATEGORIZED
    return (
      <div>
        <div
          onClick={() => toggleExpand(UNCATEGORIZED)}
          onDragOver={enableMove ? (e) => { e.preventDefault(); setDropTarget(UNCATEGORIZED) } : undefined}
          onDragLeave={enableMove ? () => setDropTarget((d) => (d === UNCATEGORIZED ? null : d)) : undefined}
          onDrop={enableMove ? (e) => onFolderDrop(e, null) : undefined}
          className={`flex cursor-pointer items-center gap-1.5 rounded-md px-1 py-1.5 pr-2 text-xs hover:bg-accent ${
            isDrop ? "bg-primary/10 ring-1 ring-primary" : ""
          }`}
        >
          <HugeiconsIcon
            icon={open ? ArrowDown01Icon : ArrowRight01Icon}
            className="size-3.5 shrink-0 text-muted-foreground"
          />
          <HugeiconsIcon icon={InboxIcon} className="size-3.5 shrink-0 text-muted-foreground" />
          <span className="truncate text-muted-foreground">未分类</span>
          <span className="ml-auto shrink-0 text-[10px] text-muted-foreground">
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
    return <p className="p-3 text-xs text-muted-foreground">加载类目…</p>
  }

  return (
    <div className={`flex flex-col gap-2 ${className ?? ""}`}>
      {/* 标签过滤 chips */}
      {showTagFilter && tags.length > 0 && (
        <div className="flex flex-wrap items-center gap-1">
          <HugeiconsIcon icon={Tag01Icon} className="size-3.5 text-muted-foreground" />
          {tags.map((tg) => {
            const active = selectedTagIds.includes(tg.id)
            return (
              <button
                key={tg.id}
                type="button"
                onClick={() => toggleTag(tg.id)}
                className={`rounded-full border px-2 py-0.5 text-[10px] transition-colors ${
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
              className="text-[10px] text-muted-foreground underline hover:text-foreground"
            >
              清除
            </button>
          )}
        </div>
      )}

      {/* 顶部操作 */}
      <div className="flex items-center justify-between">
        <p className="text-xs text-muted-foreground">
          {draggableTasksToCanvas ? "拖任务到画布建节点 · 拖入文件夹改归属" : "类目"}
        </p>
        <button
          type="button"
          onClick={() => createFolder(null)}
          className="flex items-center gap-1 text-[10px] text-muted-foreground hover:text-foreground"
          title="新建根文件夹"
        >
          <HugeiconsIcon icon={FolderAddIcon} className="size-3.5" /> 新建
        </button>
      </div>

      {/* 树体 */}
      <div className="flex flex-col">
        {tree?.roots.map((r) => renderFolder(r, 0))}
        {renderUncategorized()}
        {(!tree || tree.roots.length === 0) &&
          tasksByNode.size === 0 &&
          workflowsByNode.size === 0 && (
            <p className="px-1 py-2 text-xs text-muted-foreground">暂无类目，点「新建」建文件夹</p>
          )}
      </div>
    </div>
  )
}
