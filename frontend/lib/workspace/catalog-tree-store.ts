/**
 * 类目树 UI 状态：文件夹展开态 + 标签横切过滤态。
 * 与数据解耦（数据由 <CatalogTree> 自取），仅持有交互状态，供多处复用同一棵树时共享视图偏好。
 */
import { create } from "zustand"

export interface CatalogTreeState {
  /** 文件夹展开态：nodeId -> 是否展开（未记录视为折叠）。-1 表示「未分类」虚拟根。 */
  expanded: Record<number, boolean>
  /** 选中的标签过滤（多选，OR/AND 由消费端决定，这里只存集合）。 */
  selectedTagIds: number[]
  toggleExpand: (id: number) => void
  expand: (id: number) => void
  toggleTag: (id: number) => void
  clearTags: () => void
}

export const useCatalogTreeStore = create<CatalogTreeState>()((set, get) => ({
  expanded: {},
  selectedTagIds: [],

  toggleExpand: (id) =>
    set((s) => ({ expanded: { ...s.expanded, [id]: !s.expanded[id] } })),

  expand: (id) => set((s) => ({ expanded: { ...s.expanded, [id]: true } })),

  toggleTag: (id) => {
    const cur = get().selectedTagIds
    set({
      selectedTagIds: cur.includes(id) ? cur.filter((t) => t !== id) : [...cur, id],
    })
  },

  clearTags: () => set({ selectedTagIds: [] }),
}))
