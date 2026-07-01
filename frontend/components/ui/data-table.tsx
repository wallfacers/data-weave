"use client"

/**
 * DataTable<T>：全项目统一表格（标杆 periodic-instances-panel 的版式沉淀）。
 *   三段式布局：DataTableToolbar(shrink-0) → 表格区(flex-1, 双表共享 colgroup·固定表头·DwScroll) → Pagination(shrink-0)
 *   双模式：mode="server" 把 {filters,page,size} 交给 fetcher 打后端真实查询；
 *           mode="client" 用 lib/data-table 的纯函数在前端筛选+分页。
 *   可选多选 + 批量操作（批量写须按 outcome 三态分流，调用方在 bulkActions 内处理）。
 * 见 openspec/changes/unified-data-table/design.md。
 */

import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from "react"
import { useTranslations } from "next-intl"
import { HugeiconsIcon, type IconSvgElement } from "@hugeicons/react"
import { InboxIcon } from "@hugeicons/core-free-icons"

import { cn } from "@/lib/utils"
import { Checkbox } from "@/components/ui/checkbox"
import { Pagination } from "@/components/ui/pagination"
import { DwScroll } from "@/components/ui/dw-scroll"
import { TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { DataTableToolbar } from "@/components/ui/data-table-toolbar"
import {
  type ColumnDef,
  type FilterDef,
  type FilterValue,
  type FilterValues,
  type FilterPreset,
  type PageResult,
  type FetchQuery,
  initialFilterValues,
  applyClientFilters,
  paginate,
  totalPagesOf,
} from "@/lib/data-table"

interface DataTableProps<T> {
  columns: ColumnDef<T>[]
  getRowId?: (row: T) => string

  mode?: "client" | "server"
  /** server 模式取数器：返回归一后的 PageResult（page 1-based） */
  fetcher?: (query: FetchQuery) => Promise<PageResult<T>>
  /** client 模式数据源（前端已持有的全量数组） */
  data?: T[]

  filters?: FilterDef[]
  presets?: FilterPreset[]
  defaultFilters?: FilterValues

  pageSize?: number
  pageSizeOptions?: number[]

  selectable?: boolean
  /** 选中行时渲染的批量操作；reload 用于操作后刷新 */
  bulkActions?: (selectedIds: string[], reload: () => void) => ReactNode
  /** toolbar 右侧自定义操作区（始终渲染，不受 selectable 控制） */
  toolbarActions?: ReactNode

  emptyIcon?: IconSvgElement
  emptyTitle?: string
  emptyHint?: string

  /** 行点击（如打开详情） */
  onRowClick?: (row: T) => void
  rowClassName?: (row: T) => string | undefined
  className?: string

  /** 父级递增即触发一次 in-place 重取（不 remount、不重置 page/filter） */
  reloadSignal?: number
  /** 取数开始/结束回调（驱动控件 refreshing） */
  onLoadingChange?: (loading: boolean) => void
  /** 一次取数成功完成回调（驱动 lastUpdatedAt） */
  onLoaded?: () => void
}

const DEFAULT_SIZE = 20

export function DataTable<T>({
  columns,
  getRowId = (r) => String((r as Record<string, unknown>).id),
  mode = "server",
  fetcher,
  data,
  filters = [],
  presets,
  defaultFilters,
  pageSize = DEFAULT_SIZE,
  pageSizeOptions,
  selectable = false,
  bulkActions,
  toolbarActions,
  emptyIcon = InboxIcon,
  emptyTitle,
  emptyHint,
  onRowClick,
  rowClassName,
  className,
  reloadSignal,
  onLoadingChange,
  onLoaded,
}: DataTableProps<T>) {
  const t = useTranslations("dataTable")

  const [values, setValues] = useState<FilterValues>(() => initialFilterValues(filters, defaultFilters))
  const [page, setPage] = useState(1)
  const [size, setSize] = useState(pageSize)
  const [selected, setSelected] = useState<Set<string>>(new Set())

  // server 模式状态
  const [serverData, setServerData] = useState<PageResult<T> | null>(null)
  const [loading, setLoading] = useState(mode === "server")
  const [reloadNonce, setReloadNonce] = useState(0)
  const fetcherRef = useRef(fetcher)
  fetcherRef.current = fetcher

  // 回调 refs（不进 effect 依赖，避免重复取数）
  const onLoadingChangeRef = useRef(onLoadingChange)
  onLoadingChangeRef.current = onLoadingChange
  const onLoadedRef = useRef(onLoaded)
  onLoadedRef.current = onLoaded

  const reload = useCallback(() => setReloadNonce((n) => n + 1), [])

  useEffect(() => {
    if (mode !== "server" || !fetcherRef.current) return
    let cancelled = false
    setLoading(true)
    onLoadingChangeRef.current?.(true)
    fetcherRef
      .current({ filters: values, page, size })
      .then((res) => {
        if (!cancelled) {
          setServerData(res)
          onLoadedRef.current?.()
        }
      })
      .catch(() => {
        if (!cancelled) {
          // 已有数据时保留（FR-010 无感）；仅初始无数据时置 null
          setServerData((prev) => (prev ? prev : null))
        }
      })
      .finally(() => {
        if (!cancelled) {
          setLoading(false)
          onLoadingChangeRef.current?.(false)
        }
      })
    return () => {
      cancelled = true
    }
  }, [mode, values, page, size, reloadNonce, reloadSignal])

  // client 模式：纯函数派生
  const clientResult = useMemo<PageResult<T> | null>(() => {
    if (mode !== "client") return null
    const filtered = applyClientFilters(data ?? [], filters, values)
    return paginate(filtered, page, size)
  }, [mode, data, filters, values, page, size])

  const result = mode === "server" ? serverData : clientResult
  const items = result?.items ?? []
  const total = result?.total ?? 0
  const totalPages = totalPagesOf(total, size)

  // 筛选/预设变更 → 回第一页 + 清空选择
  function setFilter(key: string, value: FilterValue) {
    setValues((v) => ({ ...v, [key]: value }))
    setPage(1)
    setSelected(new Set())
  }
  function applyPreset(preset: FilterPreset) {
    setValues((v) => ({ ...v, ...preset.set }))
    setPage(1)
    setSelected(new Set())
  }
  function resetFilters() {
    setValues(initialFilterValues(filters, defaultFilters))
    setPage(1)
    setSelected(new Set())
  }

  // 选择
  const selectedIds = useMemo(() => Array.from(selected), [selected])
  const allSelected = items.length > 0 && items.every((r) => selected.has(getRowId(r)))
  function toggleRow(id: string) {
    setSelected((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }
  function toggleAll() {
    setSelected((prev) => (prev.size === items.length ? new Set() : new Set(items.map(getRowId))))
  }

  const colgroup = (
    <colgroup>
      {selectable && <col className="w-[4%]" />}
      {columns.map((c) => (
        <col key={c.key} style={{ width: `${c.widthPct}%` }} />
      ))}
    </colgroup>
  )

  const alignClass = (a?: "left" | "right" | "center") =>
    a === "right" ? "text-right" : a === "center" ? "text-center" : undefined

  const hasToolbar = filters.length > 0 || (presets && presets.length > 0) || (selectable && !!bulkActions) || !!toolbarActions

  return (
    <div className={cn("flex min-h-0 min-w-0 flex-1 flex-col gap-3 rounded-xl border bg-card overflow-hidden", className)}>
      {hasToolbar && (
        <div className="px-3 pt-3">
          <DataTableToolbar
            filters={filters}
            values={values}
            onChange={setFilter}
            onReset={resetFilters}
            presets={presets}
            onApplyPreset={applyPreset}
            rightSlot={
              (selectable && bulkActions) || toolbarActions ? (
                <div className="flex items-center gap-1">
                  {selectable && bulkActions && (
                    <>
                      <span className="text-xs tabular-nums text-muted-foreground">
                        {t("selected", { count: selectedIds.length })}
                      </span>
                      {bulkActions(selectedIds, reload)}
                    </>
                  )}
                  {toolbarActions}
                </div>
              ) : undefined
            }
          />
        </div>
      )}

      {loading && !result ? (
        <div className="flex flex-1 items-center justify-center py-20">
          <p className="text-sm text-muted-foreground">{t("loading")}</p>
        </div>
      ) : items.length === 0 ? (
        <div className="flex flex-1 flex-col items-center justify-center gap-3 py-20 text-center">
          <div className="flex size-12 items-center justify-center rounded-xl bg-muted text-muted-foreground">
            <HugeiconsIcon icon={emptyIcon} className="size-6" />
          </div>
          <p className="text-sm text-muted-foreground">{emptyTitle ?? t("emptyTitle")}</p>
          {emptyHint && <p className="max-w-sm text-xs text-muted-foreground">{emptyHint}</p>}
        </div>
      ) : (
        <>
          {/* 表头表（固定不滚，与数据表共享 colgroup） */}
          <div className="shrink-0 border-b bg-background">
            <table className="w-full table-fixed caption-bottom text-sm font-sans">
              {colgroup}
              <TableHeader>
                <TableRow className="hover:bg-transparent">
                  {selectable && (
                    <TableHead>
                      <Checkbox checked={allSelected} onChange={toggleAll} aria-label="select all" />
                    </TableHead>
                  )}
                  {columns.map((c) => (
                    <TableHead key={c.key} className={cn(alignClass(c.align), c.headClassName)}>
                      {c.header}
                    </TableHead>
                  ))}
                </TableRow>
              </TableHeader>
            </table>
          </div>
          {/* 数据表（仅此区域 DwScroll 纵向滚动） */}
          <DwScroll direction="vertical" className="min-h-0 flex-1">
            <table className="w-full table-fixed caption-bottom text-sm font-sans">
              {colgroup}
              <TableBody>
                {items.map((row) => {
                  const id = getRowId(row)
                  const checked = selected.has(id)
                  return (
                    <TableRow
                      key={id}
                      data-selected={checked || undefined}
                      className={cn(onRowClick && "cursor-pointer", rowClassName?.(row))}
                      onClick={onRowClick ? () => onRowClick(row) : undefined}
                    >
                      {selectable && (
                        <TableCell onClick={(e) => e.stopPropagation()}>
                          <Checkbox checked={checked} onChange={() => toggleRow(id)} />
                        </TableCell>
                      )}
                      {columns.map((c) => (
                        <TableCell
                          key={c.key}
                          className={cn("overflow-hidden", alignClass(c.align), c.cellClassName)}
                        >
                          {c.cell ? c.cell(row) : String((row as Record<string, unknown>)[c.key] ?? "—")}
                        </TableCell>
                      ))}
                    </TableRow>
                  )
                })}
              </TableBody>
            </table>
          </DwScroll>

          {totalPages > 1 && (
            <div className="shrink-0 px-3 pb-3">
              <Pagination
                page={page}
                totalPages={totalPages}
                total={total}
                size={size}
                onPageChange={setPage}
                onSizeChange={(s) => {
                  setSize(s)
                  setPage(1)
                }}
                pageSizeOptions={pageSizeOptions}
              />
            </div>
          )}
        </>
      )}
    </div>
  )
}
