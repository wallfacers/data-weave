/**
 * 类目树状态：UI 状态（展开/标签过滤）+ 数据（树/任务/工作流）+ 乐观更新方法。
 *
 * 数据由 <CatalogTree> 首次拉取后经 setData 写入；写操作（新建/保存/发布/下线/移动/改名/删除）
 * 经乐观 action 直接改本地，失败由组件 reload() 回滚。fetch 仍由组件驱动（依赖本地 tick），
 * store 不触发 fetch——这样任意 store 更新都不会误触整树重拉。
 */
import { create } from "zustand"
import type {
  CatalogTree as CatalogTreeData,
  CatalogTreeNode,
  TaskDef,
  WorkflowDef,
} from "@/lib/types"

// ── 树结构纯函数（folder 增删改复用）──

/** 把新文件夹节点插入树的正确位置（parentId=null → roots，否则递归找父）。 */
export function insertFolderIntoTree(
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
export function removeFolderFromTree(roots: CatalogTreeNode[], folderId: number): CatalogTreeNode[] {
  return roots
    .filter((r) => r.id !== folderId)
    .map((r) => ({ ...r, children: removeFolderFromTree(r.children ?? [], folderId) }))
}

/** 更新树中指定文件夹的名称。 */
export function renameFolderInTree(
  roots: CatalogTreeNode[],
  folderId: number,
  name: string,
): CatalogTreeNode[] {
  return roots.map((r) =>
    r.id === folderId
      ? { ...r, name }
      : { ...r, children: renameFolderInTree(r.children ?? [], folderId, name) },
  )
}

export interface CatalogTreeState {
  // ── UI 状态：文件夹展开态 + 标签横切过滤态（与数据解耦，供多处复用同一棵树时共享视图偏好）──
  /** 文件夹展开态：nodeId -> 是否展开（未记录视为折叠）。-1 表示「未分类」虚拟根。 */
  expanded: Record<number, boolean>
  /** 选中的标签过滤（多选，OR/AND 由消费端决定，这里只存集合）。 */
  selectedTagIds: number[]
  toggleExpand: (id: number) => void
  expand: (id: number) => void
  toggleTag: (id: number) => void
  clearTags: () => void

  // ── 数据 ──
  tree: CatalogTreeData | null
  tasks: TaskDef[]
  workflows: WorkflowDef[]
  loading: boolean

  // ── 数据 action（纯状态操作，不调 API、不触发 fetch）──
  /** CatalogTree fetch 后写入（partial 合并）。 */
  setData: (
    partial: Partial<Pick<CatalogTreeState, "tree" | "tasks" | "workflows" | "loading">>,
  ) => void
  /** 按 id 合并/追加任务（新建或全量更新）。 */
  upsertTask: (task: TaskDef) => void
  upsertWorkflow: (wf: WorkflowDef) => void
  /** 按 id 局部更新（保存草稿/发布/下线/移动归类）。 */
  updateTask: (id: number, patch: Partial<TaskDef>) => void
  updateWorkflow: (id: number, patch: Partial<WorkflowDef>) => void
  removeTask: (id: number) => void
  removeWorkflow: (id: number) => void
  /** 文件夹增删改（操作 store.tree 结构）。 */
  insertFolder: (parentId: number | null, node: CatalogTreeNode) => void
  removeFolder: (folderId: number) => void
  renameFolder: (folderId: number, name: string) => void
}

export const useCatalogTreeStore = create<CatalogTreeState>()((set) => ({
  expanded: {},
  selectedTagIds: [],

  toggleExpand: (id) => set((s) => ({ expanded: { ...s.expanded, [id]: !s.expanded[id] } })),
  expand: (id) => set((s) => ({ expanded: { ...s.expanded, [id]: true } })),
  toggleTag: (id) =>
    set((s) => ({
      selectedTagIds: s.selectedTagIds.includes(id)
        ? s.selectedTagIds.filter((t) => t !== id)
        : [...s.selectedTagIds, id],
    })),
  clearTags: () => set({ selectedTagIds: [] }),

  tree: null,
  tasks: [],
  workflows: [],
  loading: true,

  setData: (partial) => set(partial),

  upsertTask: (task) =>
    set((s) => ({
      tasks: s.tasks.some((x) => x.id === task.id)
        ? s.tasks.map((x) => (x.id === task.id ? task : x))
        : [...s.tasks, task],
    })),
  upsertWorkflow: (wf) =>
    set((s) => ({
      workflows: s.workflows.some((x) => x.id === wf.id)
        ? s.workflows.map((x) => (x.id === wf.id ? wf : x))
        : [...s.workflows, wf],
    })),
  updateTask: (id, patch) =>
    set((s) => ({ tasks: s.tasks.map((x) => (x.id === id ? { ...x, ...patch } : x)) })),
  updateWorkflow: (id, patch) =>
    set((s) => ({ workflows: s.workflows.map((x) => (x.id === id ? { ...x, ...patch } : x)) })),
  removeTask: (id) => set((s) => ({ tasks: s.tasks.filter((x) => x.id !== id) })),
  removeWorkflow: (id) => set((s) => ({ workflows: s.workflows.filter((x) => x.id !== id) })),

  insertFolder: (parentId, node) =>
    set((s) => ({
      tree: s.tree ? { ...s.tree, roots: insertFolderIntoTree(s.tree.roots, parentId, node) } : s.tree,
    })),
  removeFolder: (folderId) =>
    set((s) => ({
      tree: s.tree ? { ...s.tree, roots: removeFolderFromTree(s.tree.roots, folderId) } : s.tree,
    })),
  renameFolder: (folderId, name) =>
    set((s) => ({
      tree: s.tree ? { ...s.tree, roots: renameFolderInTree(s.tree.roots, folderId, name) } : s.tree,
    })),
}))
