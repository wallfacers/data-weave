/**
 * DataTable 统一类型与纯逻辑工具。
 *
 * 组件分两层(见 openspec/changes/unified-data-table/design.md D1/D2):
 *   - DataTableToolbar：按 FilterDef 渲染筛选 UI，管理筛选值
 *   - DataTable：三段式布局 + 双表固定表头 + DwScroll + Pagination + 多选批量 + 空/加载
 *
 * 本模块只放「无 DOM 依赖」的类型与纯函数，便于 vitest 直接覆盖 client 模式逻辑。
 * client/server 双模式：server 模式把 {filters,page,size} 交给 fetcher 打后端真实查询；
 * client 模式用这里的 applyClientFilters + paginate 在前端处理。
 */

import type { ReactNode } from "react"

// ──────────────────────────────── 列 ────────────────────────────────

export interface ColumnDef<T> {
  /** 字段键（也用于 React key / 默认取值） */
  key: string
  /** 已翻译好的表头文案（调用方传 t("...")） */
  header: string
  /** 列宽百分比（colgroup + table-fixed，所有列加总应≈100） */
  widthPct: number
  align?: "left" | "right" | "center"
  headClassName?: string
  cellClassName?: string
  /** 自定义单元格渲染；缺省时渲染 String(row[key]) */
  cell?: (row: T) => ReactNode
}

// ──────────────────────────────── 筛选 ────────────────────────────────

export type FilterKind =
  | "search" // 防抖多字段文本
  | "select" // 单选下拉（选项多，如 state 8 项）
  | "segmented" // 二元/小枚举段控（含「全部」）
  | "multiSelect" // 枚举多选
  | "date" // 单个日期（后端单值 query，如 bizDate）
  | "dateRange" // 日期区间（后端 keyFrom/keyTo）
  | "toggle" // 布尔快捷

export interface FilterOption {
  value: string
  label: string
}

export interface FilterDef {
  key: string
  /** 已翻译好的标签 */
  label: string
  kind: FilterKind
  /** segmented / multiSelect 的选项（已翻译） */
  options?: FilterOption[]
  /** primary 常驻工具栏；advanced 收进「更多筛选」。缺省 primary */
  tier?: "primary" | "advanced"
  placeholder?: string
  /** 触发器宽度 class（如 "w-40"） */
  width?: string
  /** client 模式 search：参与匹配的行字段；dateRange：取值字段（缺省 key） */
  matchKeys?: string[]
  /** date/dateRange 类型：是否显示时分秒选择。默认 false */
  showTime?: boolean
}

/** 单个筛选值：随 kind 不同 */
export type FilterValue = string | string[] | DateRangeValue | boolean
export interface DateRangeValue {
  from?: string
  to?: string
}
export type FilterValues = Record<string, FilterValue>

/** 语义化快捷预设：一次设好一组筛选值 */
export interface FilterPreset {
  key: string
  /** 已翻译好的预设名 */
  label: string
  set: FilterValues
}

// ──────────────────────────────── 分页 ────────────────────────────────

/** 归一后的分页结果（page 一律 1-based） */
export interface PageResult<T> {
  items: T[]
  total: number
  page: number
  size: number
}

export interface FetchQuery {
  filters: FilterValues
  page: number
  size: number
}

// ──────────────────────────────── 纯逻辑 ────────────────────────────────

/** 某 kind 的「空值」（= 未筛选） */
export function emptyValueFor(kind: FilterKind): FilterValue {
  switch (kind) {
    case "multiSelect":
      return []
    case "dateRange":
      return {}
    case "toggle":
      return false
    default:
      return ""
  }
}

/** 某筛选值是否「已激活」（非空） */
export function isFilterActive(kind: FilterKind, value: FilterValue | undefined): boolean {
  if (value == null) return false
  switch (kind) {
    case "multiSelect":
      return Array.isArray(value) && value.length > 0
    case "dateRange": {
      const r = value as DateRangeValue
      return Boolean(r.from || r.to)
    }
    case "toggle":
      return value === true
    default:
      return typeof value === "string" && value.length > 0
  }
}

/** 统计已激活筛选条数（驱动工具栏计数与「清空」可用性） */
export function countActiveFilters(defs: FilterDef[], values: FilterValues): number {
  return defs.reduce((n, d) => (isFilterActive(d.kind, values[d.key]) ? n + 1 : n), 0)
}

/** 全部筛选的初始空值表 */
export function initialFilterValues(defs: FilterDef[], defaults?: FilterValues): FilterValues {
  const base: FilterValues = {}
  for (const d of defs) base[d.key] = emptyValueFor(d.kind)
  return { ...base, ...(defaults ?? {}) }
}

/** server 模式：把筛选值 + 分页拍平成后端 query 参数（数组→逗号；区间→key.from/key.to；toggle→true） */
export function toQueryParams(query: FetchQuery, defs: FilterDef[]): URLSearchParams {
  const qs = new URLSearchParams()
  for (const d of defs) {
    const v = query.filters[d.key]
    if (!isFilterActive(d.kind, v)) continue
    if (d.kind === "multiSelect") {
      qs.set(d.key, (v as string[]).join(","))
    } else if (d.kind === "dateRange") {
      const r = v as DateRangeValue
      if (r.from) qs.set(`${d.key}From`, r.from)
      if (r.to) qs.set(`${d.key}To`, r.to)
    } else if (d.kind === "toggle") {
      qs.set(d.key, "true")
    } else {
      qs.set(d.key, String(v))
    }
  }
  qs.set("page", String(query.page))
  qs.set("size", String(query.size))
  return qs
}

function fieldValue<T>(row: T, key: string): unknown {
  return (row as Record<string, unknown>)[key]
}

/** client 模式：单条筛选是否匹配一行 */
export function matchesFilter<T>(row: T, def: FilterDef, value: FilterValue | undefined): boolean {
  if (!isFilterActive(def.kind, value)) return true
  switch (def.kind) {
    case "search": {
      const needle = String(value).toLowerCase()
      const keys = def.matchKeys && def.matchKeys.length > 0 ? def.matchKeys : [def.key]
      return keys.some((k) => {
        const cell = fieldValue(row, k)
        return cell != null && String(cell).toLowerCase().includes(needle)
      })
    }
    case "segmented":
    case "select":
    case "date":
      return String(fieldValue(row, def.key)) === String(value)
    case "multiSelect": {
      const set = value as string[]
      return set.includes(String(fieldValue(row, def.key)))
    }
    case "toggle":
      return Boolean(fieldValue(row, def.key))
    case "dateRange": {
      const r = value as DateRangeValue
      const cellRaw = fieldValue(row, def.matchKeys?.[0] ?? def.key)
      if (cellRaw == null) return false
      const cell = String(cellRaw)
      if (r.from && cell < r.from) return false
      if (r.to && cell > r.to) return false
      return true
    }
    default:
      return true
  }
}

/** client 模式：应用全部筛选 */
export function applyClientFilters<T>(rows: T[], defs: FilterDef[], values: FilterValues): T[] {
  return rows.filter((row) => defs.every((d) => matchesFilter(row, d, values[d.key])))
}

/** 截取某一页（page 1-based） */
export function paginate<T>(rows: T[], page: number, size: number): PageResult<T> {
  const total = rows.length
  const safeSize = Math.max(1, size)
  const totalPages = Math.max(1, Math.ceil(total / safeSize))
  const safePage = Math.min(Math.max(1, page), totalPages)
  const start = (safePage - 1) * safeSize
  return { items: rows.slice(start, start + safeSize), total, page: safePage, size: safeSize }
}

/** 总页数（至少 1） */
export function totalPagesOf(total: number, size: number): number {
  return Math.max(1, Math.ceil(total / Math.max(1, size)))
}
