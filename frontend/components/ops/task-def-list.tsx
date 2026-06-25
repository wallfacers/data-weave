"use client"

import { useCallback, useEffect, useMemo, useState } from "react"
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import { BoxIcon, PlayIcon, StopIcon, Edit02Icon, Delete02Icon } from "@hugeicons/core-free-icons"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { DataTable } from "@/components/ui/data-table"
import { type ColumnDef, type FilterDef, type FilterPreset, type FetchQuery, type PageResult, toQueryParams } from "@/lib/data-table"
import { type TaskDef, API_BASE, authFetch, type ApiResponse } from "@/lib/types"
import { useFormatDateTime } from "@/hooks/use-format-date-time"

interface TaskDefListProps {
  onEdit?: (task: TaskDef) => void
}

interface DatasourceOption { id: number; name: string }
interface TagOption { id: number; name: string }

export function TaskDefList({ onEdit }: TaskDefListProps) {
  const t = useTranslations("taskDefList")
  const formatDateTime = useFormatDateTime()
  const [refreshKey, setRefreshKey] = useState(0)
  const [datasourceOpts, setDatasourceOpts] = useState<DatasourceOption[]>([])
  const [tagOpts, setTagOpts] = useState<TagOption[]>([])

  // 加载数据源/标签选项（高级筛选下拉用）
  useEffect(() => {
    authFetch(`${API_BASE}/api/datasources?projectId=1`)
      .then((r) => (r.ok ? (r.json() as Promise<ApiResponse<unknown>>) : Promise.resolve(null)))
      .then((json) => {
        if (!json || json.code !== 0) return
        const arr = Array.isArray(json.data) ? json.data : []
        setDatasourceOpts(
          arr.map((d: Record<string, unknown>) => ({ id: d.id as number, name: d.name as string })),
        )
      })
      .catch(() => {})
    authFetch(`${API_BASE}/api/tags`)
      .then((r) => (r.ok ? (r.json() as Promise<ApiResponse<unknown>>) : Promise.resolve(null)))
      .then((json) => {
        if (!json || json.code !== 0) return
        const arr = Array.isArray(json.data) ? json.data : []
        setTagOpts(
          arr.map((tg: Record<string, unknown>) => ({ id: tg.id as number, name: tg.name as string })),
        )
      })
      .catch(() => {})
  }, [])

  const filters = useMemo<FilterDef[]>(
    () => [
      { key: "keyword", label: t("filterName"), kind: "search", placeholder: t("filterNamePh"), width: "w-44" },
      {
        key: "status",
        label: t("filterStatus"),
        kind: "segmented",
        width: "w-36",
        options: [
          { value: "", label: t("statusAll") },
          { value: "DRAFT", label: t("statusDraft") },
          { value: "ONLINE", label: t("statusOnline") },
        ],
      },
      {
        key: "type",
        label: t("filterType"),
        kind: "multiSelect",
        width: "w-36",
        options: [
          { value: "SQL", label: "SQL" },
          { value: "SHELL", label: "Shell" },
          { value: "PYTHON", label: "Python" },
          { value: "ECHO", label: "Echo" },
        ],
      },
      { key: "ownerId", label: t("filterMine"), kind: "toggle" },
      {
        key: "tagId",
        label: t("filterTag"),
        kind: "select",
        tier: "advanced",
        width: "w-36",
        options: tagOpts.map((tg) => ({ value: String(tg.id), label: tg.name })),
      },
      {
        key: "datasourceId",
        label: t("filterDatasource"),
        kind: "select",
        tier: "advanced",
        width: "w-40",
        options: datasourceOpts.map((ds) => ({ value: String(ds.id), label: ds.name })),
      },
      { key: "frozen", label: t("filterFrozen"), kind: "toggle", tier: "advanced" },
    ],
    [t, tagOpts, datasourceOpts],
  )

  const presets = useMemo<FilterPreset[]>(
    () => [
      {
        key: "myDrafts",
        label: t("presetMyDrafts"),
        set: { status: "DRAFT", ownerId: true, keyword: "", type: [], tagId: "", datasourceId: "", frozen: false },
      },
      {
        key: "frozenTasks",
        label: t("presetFrozen"),
        set: { frozen: true, keyword: "", status: "", type: [], ownerId: false, tagId: "", datasourceId: "" },
      },
    ],
    [t],
  )

  const defaultFilters = useMemo(
    () => ({
      keyword: "",
      status: "",
      type: [] as string[],
      ownerId: false as boolean | string,
      tagId: "",
      datasourceId: "",
      frozen: false as boolean | string,
    }),
    [],
  )

  const fetcher = useCallback(
    async (query: FetchQuery): Promise<PageResult<TaskDef>> => {
      const qs = toQueryParams(query, filters)
      // ownerId toggle → 特殊值 "me"（toQueryParams 写为 "true"，需替换）
      if (query.filters.ownerId === true) {
        qs.set("ownerId", "me")
      }
      // frozen toggle → 值 "1"
      if (query.filters.frozen === true) {
        qs.set("frozen", "1")
      }
      const res = await authFetch(`${API_BASE}/api/tasks?${qs.toString()}`)
      if (!res.ok) return { items: [], total: 0, page: query.page, size: query.size }
      const json = (await res.json()) as ApiResponse<unknown>
      if (json.code !== 0 || !json.data) return { items: [], total: 0, page: query.page, size: query.size }
      const d = json.data as Record<string, unknown>
      if (Array.isArray(d.content)) {
        return {
          items: d.content as TaskDef[],
          total: (d.totalElements as number) ?? (d.content as unknown[]).length,
          page: ((d.number as number) ?? 0) + 1,
          size: (d.size as number) ?? query.size,
        }
      }
      return { items: [], total: 0, page: query.page, size: query.size }
    },
    [filters],
  )

  const reload = useCallback(() => setRefreshKey((k) => k + 1), [])

  async function doAction(task: TaskDef, action: string) {
    try {
      if (action === "publish") {
        await authFetch(`${API_BASE}/api/tasks/${task.id}/publish`, { method: "POST" })
      } else if (action === "offline") {
        await authFetch(`${API_BASE}/api/tasks/${task.id}/offline`, { method: "POST" })
      } else if (action === "delete") {
        await authFetch(`${API_BASE}/api/tasks/${task.id}`, { method: "DELETE" })
      }
      reload()
    } catch {
      /* ignore */
    }
  }

  const columns = useMemo<ColumnDef<TaskDef>[]>(
    () => [
      {
        key: "name",
        header: t("colName"),
        widthPct: 28,
        cell: (r) => (
          <div className="align-top">
            <div className="truncate font-medium" title={r.name}>
              {r.name}
            </div>
            {r.description && (
              <div className="truncate text-xs text-muted-foreground" title={r.description}>
                {r.description}
              </div>
            )}
          </div>
        ),
      },
      { key: "type", header: t("colType"), widthPct: 8, cellClassName: "font-mono text-xs" },
      {
        key: "status",
        header: t("colStatus"),
        widthPct: 8,
        cell: (r) =>
          r.status === "ONLINE" ? (
            <Badge variant="success">{t("statusOnline")}</Badge>
          ) : (
            <Badge variant="outline" className="text-muted-foreground">
              {t("statusDraft")}
            </Badge>
          ),
      },
      {
        key: "priority",
        header: t("colPriority"),
        widthPct: 6,
        align: "right",
        cellClassName: "tabular-nums",
        cell: (r) => String(r.priority ?? 5),
      },
      {
        key: "version",
        header: t("colVersion"),
        widthPct: 6,
        align: "right",
        cellClassName: "tabular-nums",
        cell: (r) => `v${r.currentVersionNo}`,
      },
      {
        key: "createdAt",
        header: t("colCreatedAt"),
        widthPct: 14,
        cellClassName: "tabular-nums text-xs",
        cell: (r) => formatDateTime(r.createdAt),
      },
      {
        key: "actions",
        header: t("colActions"),
        widthPct: 14,
        align: "right",
        cell: (r) => (
          <div className="flex justify-end gap-1">
            <Button
              variant="ghost"
              size="sm"
              className="h-6 px-2 text-xs"
              onClick={() => onEdit?.(r)}
            >
              <HugeiconsIcon icon={Edit02Icon} className="size-3" />
              {t("btnEdit")}
            </Button>
            {r.status === "DRAFT" && (
              <Button
                variant="ghost"
                size="sm"
                className="h-6 px-2 text-xs"
                onClick={() => doAction(r, "publish")}
              >
                <HugeiconsIcon icon={PlayIcon} className="size-3" />
                {t("btnPublish")}
              </Button>
            )}
            {r.status === "ONLINE" && (
              <Button
                variant="ghost"
                size="sm"
                className="h-6 px-2 text-xs text-destructive"
                onClick={() => doAction(r, "offline")}
              >
                <HugeiconsIcon icon={StopIcon} className="size-3" />
                {t("btnOffline")}
              </Button>
            )}
            {r.status === "DRAFT" && (
              <Button
                variant="ghost"
                size="sm"
                className="h-6 px-2 text-xs text-destructive"
                onClick={() => doAction(r, "delete")}
              >
                <HugeiconsIcon icon={Delete02Icon} className="size-3" />
                {t("btnDelete")}
              </Button>
            )}
          </div>
        ),
      },
    ],
    [t, formatDateTime, onEdit, reload],
  )

  return (
    <DataTable<TaskDef>
      key={refreshKey}
      columns={columns}
      getRowId={(r) => String(r.id)}
      mode="server"
      fetcher={fetcher}
      filters={filters}
      presets={presets}
      defaultFilters={defaultFilters}
      emptyIcon={BoxIcon}
      emptyTitle={t("emptyTitle")}
      emptyHint={t("emptyHint")}
    />
  )
}
