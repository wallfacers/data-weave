"use client"

/**
 * 周期实例面板：筛选 + 分页 + 多选 + 批量操作 + 表格。
 *
 * 调契约①：
 *   GET  /api/ops/instances?runMode=&state=&taskId=&bizDate=&page=&size=
 *   POST /api/ops/instances/batch { ids, op } → { code, data: BatchResult, outcome }
 *
 * 批量操作按 outcome 三态分流：
 *   EXECUTED → 成功提示
 *   PENDING_APPROVAL → 待批提示（绝不能因 code===0 误判为已执行）
 *   REJECTED → 拒绝提示
 */

import { useCallback, useEffect, useMemo, useState } from "react"
import { useLocale, useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  BoxIcon,
  FilterIcon,
  PlayIcon,
  StopIcon,
  CheckmarkCircle01Icon,
  RefreshIcon,
} from "@hugeicons/core-free-icons"
import { toast } from "sonner"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Checkbox } from "@/components/ui/checkbox"
import { DropdownSelect } from "@/components/ui/select"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { DwScroll } from "@/components/ui/dw-scroll"
import {
  API_BASE,
  authFetch,
  formatDateTime,
  type ApiResponse,
} from "@/lib/types"
import { useWorkspaceStore } from "@/lib/workspace/store"

/** 契约① InstanceRow */
interface InstanceRow {
  id: string
  taskDefId: number
  taskDefName: string
  workflowId?: string | null
  runMode: "PERIODIC" | "BACKFILL" | "MANUAL" | "TEST"
  state: string
  bizDate: string
  startedAt?: string | null
  finishedAt?: string | null
  durationMs?: number | null
}

interface InstancesResponse {
  items: InstanceRow[]
  total: number
  page: number
  size: number
}

interface BatchRowResult {
  id: string
  outcome: "EXECUTED" | "PENDING_APPROVAL" | "REJECTED"
  approvalId?: string | null
}

interface BatchResult {
  requested: number
  accepted: number
  results: BatchRowResult[]
}

interface BatchResponse {
  code: number
  data: BatchResult | null
  outcome: "EXECUTED" | "PENDING_APPROVAL" | "REJECTED"
  message?: string
}

interface Filters {
  runMode: string
  state: string
  taskId: string
  bizDate: string
}

const DEFAULT_FILTERS: Filters = { runMode: "", state: "", taskId: "", bizDate: "" }
const DEFAULT_PAGE_SIZE = 20

const STATE_BADGE_VARIANT: Record<string, "success" | "info" | "warning" | "destructive" | "outline"> = {
  SUCCESS: "success",
  RUNNING: "success",
  WAITING: "warning",
  WAIT_RETRY: "info",
  DISPATCHED: "info",
  NOT_RUN: "outline",
  FAILED: "destructive",
  KILLED: "destructive",
  STOPPED: "destructive",
  PAUSED: "warning",
}

const RUNMODE_OPTIONS = [
  { value: "", label: "filterAll" },
  { value: "PERIODIC", label: "filterRunModePeriodic" },
  { value: "BACKFILL", label: "filterRunModeBackfill" },
  { value: "MANUAL", label: "filterRunModeManual" },
  { value: "TEST", label: "filterRunModeTest" },
] as const

const STATE_OPTIONS = [
  { value: "", label: "filterAll" },
  { value: "RUNNING", label: "stateRunning" },
  { value: "SUCCESS", label: "stateSuccess" },
  { value: "FAILED", label: "stateFailed" },
  { value: "WAITING", label: "stateWaiting" },
  { value: "NOT_RUN", label: "stateNotRun" },
  { value: "STOPPED", label: "stateStopped" },
  { value: "KILLED", label: "stateKilled" },
  { value: "PAUSED", label: "statePaused" },
] as const

type OpsKey = keyof typeof RUNMODE_OPTIONS[number] extends never
  ? never
  :
      | "filterAll"
      | "filterRunModePeriodic"
      | "filterRunModeBackfill"
      | "filterRunModeManual"
      | "filterRunModeTest"
      | "stateRunning"
      | "stateSuccess"
      | "stateFailed"
      | "stateWaiting"
      | "stateNotRun"
      | "stateStopped"
      | "stateKilled"
      | "statePaused"

export function PeriodicInstancesPanel({
  initialFilter,
}: {
  initialFilter?: Record<string, string>
}) {
  const t = useTranslations("ops")
  const locale = useLocale()
  const open = useWorkspaceStore((s) => s.open)

  const [filters, setFilters] = useState<Filters>(() => ({
    ...DEFAULT_FILTERS,
    ...(initialFilter ?? {}),
  }))
  const [page, setPage] = useState(0)
  const [size] = useState(DEFAULT_PAGE_SIZE)
  const [data, setData] = useState<InstancesResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [batchBusy, setBatchBusy] = useState(false)

  const fetchList = useCallback(async () => {
    setLoading(true)
    try {
      const qs = new URLSearchParams()
      if (filters.runMode) qs.set("runMode", filters.runMode)
      if (filters.state) qs.set("state", filters.state)
      if (filters.taskId) qs.set("taskId", filters.taskId)
      if (filters.bizDate) qs.set("bizDate", filters.bizDate)
      qs.set("page", String(page))
      qs.set("size", String(size))
      const res = await authFetch(`${API_BASE}/api/ops/instances?${qs.toString()}`)
      if (!res.ok) {
        setData(null)
        return
      }
      const json = (await res.json()) as ApiResponse<InstancesResponse>
      if (json.code === 0 && json.data) setData(json.data)
      else setData(null)
    } catch {
      setData(null)
    } finally {
      setLoading(false)
    }
  }, [filters, page, size])

  useEffect(() => {
    fetchList()
  }, [fetchList])

  // 切换筛选回第一页
  function updateFilter<K extends keyof Filters>(k: K, v: string) {
    setFilters((f) => ({ ...f, [k]: v }))
    setPage(0)
    setSelected(new Set())
  }

  function resetFilters() {
    setFilters(DEFAULT_FILTERS)
    setPage(0)
    setSelected(new Set())
  }

  const items = data?.items ?? []
  const total = data?.total ?? 0
  const totalPages = Math.max(1, Math.ceil(total / size))
  const allSelected = items.length > 0 && items.every((i) => selected.has(i.id))

  function toggleSelect(id: string) {
    setSelected((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  function toggleSelectAll() {
    setSelected((prev) => {
      if (prev.size === items.length) return new Set()
      return new Set(items.map((i) => i.id))
    })
  }

  async function runBatch(op: "rerun" | "kill" | "set-success") {
    if (selected.size === 0) {
      toast.error(t("batchNoSelection"))
      return
    }
    const opLabel =
      op === "rerun" ? t("batchRerun") : op === "kill" ? t("batchKill") : t("batchSetSuccess")
    setBatchBusy(true)
    try {
      const res = await authFetch(`${API_BASE}/api/ops/instances/batch`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ ids: Array.from(selected), op }),
      })
      const json = (await res.json().catch(() => null)) as BatchResponse | null
      if (!json || json.code !== 0) {
        toast.error(t("actionFailed", { label: opLabel, msg: json?.message ?? `HTTP ${res.status}` }))
        return
      }
      // ★ 按 outcome 三态分流，绝不只看 code===0
      summarizeOutcome(json, opLabel)
      setSelected(new Set())
      fetchList()
    } catch (e) {
      toast.error(t("actionFailed", { label: opLabel, msg: e instanceof Error ? e.message : t("networkError") }))
    } finally {
      setBatchBusy(false)
    }
  }

  function summarizeOutcome(json: BatchResponse, opLabel: string) {
    const results = json.data?.results ?? []
    const executed = results.filter((r) => r.outcome === "EXECUTED").length
    const pending = results.filter((r) => r.outcome === "PENDING_APPROVAL").length
    const rejected = results.filter((r) => r.outcome === "REJECTED").length

    if (pending > 0 && executed === 0 && rejected === 0) {
      toast.info(`${opLabel} · ${t("outcomePendingApproval")} (${pending})`)
      return
    }
    const parts: string[] = []
    if (executed > 0) parts.push(`${t("outcomeExecuted")} ${executed}`)
    if (pending > 0) parts.push(`${t("outcomePendingApproval")} ${pending}`)
    if (rejected > 0) parts.push(`${t("outcomeRejected")} ${rejected}`)
    const summary = parts.join(" · ")
    if (rejected > 0) toast.error(`${opLabel} · ${summary}`)
    else if (pending > 0) toast.info(`${opLabel} · ${summary}`)
    else toast.success(`${opLabel} · ${summary}`)
  }

  function stateBadge(state: string) {
    const variant = STATE_BADGE_VARIANT[state] ?? "outline"
    const muted = variant === "outline" ? "text-muted-foreground" : undefined
    const labelKey = stateToI18nKey(state)
    return (
      <Badge variant={variant} className={muted}>
        {labelKey ? t(labelKey as never) : state}
      </Badge>
    )
  }

  function stateToI18nKey(state: string): string | null {
    const map: Record<string, string> = {
      SUCCESS: "stateSuccess",
      RUNNING: "stateRunning",
      FAILED: "stateFailed",
      WAITING: "stateWaiting",
      WAIT_RETRY: "stateWaiting",
      DISPATCHED: "stateRunning",
      NOT_RUN: "stateNotRun",
      STOPPED: "stateStopped",
      KILLED: "stateKilled",
      PAUSED: "statePaused",
    }
    return map[state] ?? null
  }

  function formatDuration(ms: number | null | undefined): string {
    if (ms == null) return "—"
    const sec = Math.round(ms / 1000)
    if (sec < 60) return `${sec}s`
    const min = Math.floor(sec / 60)
    const s = sec % 60
    if (min < 60) return `${min}m ${s}s`
    const h = Math.floor(min / 60)
    const m = min % 60
    return `${h}h ${m}m`
  }

  return (
    <DwScroll className="flex-1" innerClassName="flex flex-col gap-3 p-5">
      {/* 筛选栏 */}
      <div className="flex flex-wrap items-center gap-2">
        <HugeiconsIcon icon={FilterIcon} className="size-4 text-muted-foreground" />
        <DropdownSelect
          value={filters.runMode}
          onChange={(v) => updateFilter("runMode", v)}
          options={RUNMODE_OPTIONS.map((o) => ({ value: o.value, label: t(o.label as never) }))}
          placeholder={t("filterRunMode")}
          triggerClassName="h-8"
        />
        <DropdownSelect
          value={filters.state}
          onChange={(v) => updateFilter("state", v)}
          options={STATE_OPTIONS.map((o) => ({ value: o.value, label: t(o.label as never) }))}
          placeholder={t("filterState")}
          triggerClassName="h-8"
        />
        <Input
          className="h-8 w-32 text-sm"
          placeholder={t("filterTaskId")}
          value={filters.taskId}
          onChange={(e) => updateFilter("taskId", e.target.value)}
        />
        <Input
          type="date"
          className="h-8 w-40 text-sm"
          placeholder={t("filterBizDate")}
          value={filters.bizDate}
          onChange={(e) => updateFilter("bizDate", e.target.value)}
        />
        <Button variant="ghost" size="sm" className="h-8 text-xs" onClick={resetFilters}>
          <HugeiconsIcon icon={RefreshIcon} className="size-3.5" />
          {t("filterReset")}
        </Button>
        <div className="flex-1" />
        {/* 批量操作 */}
        <div className="flex items-center gap-1">
          <span className="text-xs tabular-nums text-muted-foreground">
            {t("batchSelected", { count: selected.size })}
          </span>
          <Button
            size="sm"
            className="h-8 text-xs"
            disabled={selected.size === 0 || batchBusy}
            onClick={() => runBatch("rerun")}
          >
            <HugeiconsIcon icon={PlayIcon} className="size-3.5" />
            {t("batchRerun")}
          </Button>
          <Button
            size="sm"
            className="h-8 text-xs"
            disabled={selected.size === 0 || batchBusy}
            onClick={() => runBatch("set-success")}
          >
            <HugeiconsIcon icon={CheckmarkCircle01Icon} className="size-3.5" />
            {t("batchSetSuccess")}
          </Button>
          <Button
            size="sm"
            variant="destructive"
            className="h-8 text-xs"
            disabled={selected.size === 0 || batchBusy}
            onClick={() => runBatch("kill")}
          >
            <HugeiconsIcon icon={StopIcon} className="size-3.5" />
            {t("batchKill")}
          </Button>
        </div>
      </div>

      {/* 表格 */}
      {loading && !data ? (
        <div className="flex flex-1 items-center justify-center py-20">
          <p className="text-sm text-muted-foreground">Loading</p>
        </div>
      ) : items.length === 0 ? (
        <div className="flex flex-1 flex-col items-center justify-center gap-3 py-20 text-center">
          <div className="flex size-12 items-center justify-center rounded-xl bg-muted text-muted-foreground">
            <HugeiconsIcon icon={BoxIcon} className="size-6" />
          </div>
          <p className="text-sm text-muted-foreground">{t("emptyTitle")}</p>
          <p className="max-w-sm text-xs text-muted-foreground">{t("emptyHint")}</p>
        </div>
      ) : (
        <>
          <div className="font-sans">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="w-10">
                    <Checkbox
                      checked={allSelected}
                      onChange={toggleSelectAll}
                      aria-label="select all"
                    />
                  </TableHead>
                  <TableHead className="w-24 font-mono">{t("colInstance")}</TableHead>
                  <TableHead className="w-40">{t("colTaskName")}</TableHead>
                  <TableHead className="w-24">{t("colState")}</TableHead>
                  <TableHead className="w-28">{t("colBizDate")}</TableHead>
                  <TableHead className="w-36">{t("colStartedAt")}</TableHead>
                  <TableHead className="w-36">{t("colFinishedAt")}</TableHead>
                  <TableHead className="w-20 text-right">{t("colDuration")}</TableHead>
                  <TableHead className="w-20 text-right">{t("colActions")}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {items.map((inst) => {
                  const checked = selected.has(inst.id)
                  return (
                    <TableRow key={inst.id} data-selected={checked || undefined}>
                      <TableCell>
                        <Checkbox checked={checked} onChange={() => toggleSelect(inst.id)} />
                      </TableCell>
                      <TableCell className="font-mono tabular-nums text-xs">
                        {inst.id.slice(0, 8)}
                      </TableCell>
                      <TableCell className="max-w-0 truncate" title={inst.taskDefName}>
                        <div className="truncate font-medium">{inst.taskDefName}</div>
                        <div className="truncate text-xs text-muted-foreground">
                          #{inst.taskDefId}
                        </div>
                      </TableCell>
                      <TableCell>{stateBadge(inst.state)}</TableCell>
                      <TableCell className="tabular-nums text-xs">{inst.bizDate}</TableCell>
                      <TableCell className="tabular-nums text-xs">
                        {formatDateTime(inst.startedAt ?? null, locale)}
                      </TableCell>
                      <TableCell className="tabular-nums text-xs">
                        {formatDateTime(inst.finishedAt ?? null, locale)}
                      </TableCell>
                      <TableCell className="text-right tabular-nums text-xs">
                        {formatDuration(inst.durationMs)}
                      </TableCell>
                      <TableCell className="text-right">
                        <div className="flex justify-end gap-1">
                          <Button
                            variant="ghost"
                            size="sm"
                            className="h-6 px-2 text-xs"
                            onClick={() =>
                              open("workflow-instance-detail", { instanceId: inst.id })
                            }
                          >
                            {t("btnLog")}
                          </Button>
                        </div>
                      </TableCell>
                    </TableRow>
                  )
                })}
              </TableBody>
            </Table>
          </div>

          {totalPages > 1 && (
            <div className="flex items-center justify-between px-1">
              <span className="text-xs text-muted-foreground">
                {t("pageInfo", { total, page: page + 1, totalPages })}
              </span>
              <div className="flex gap-1">
                <Button
                  variant="outline"
                  size="sm"
                  className="h-7 text-xs"
                  disabled={page === 0}
                  onClick={() => setPage((p) => p - 1)}
                >
                  {t("prevPage")}
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  className="h-7 text-xs"
                  disabled={page >= totalPages - 1}
                  onClick={() => setPage((p) => p + 1)}
                >
                  {t("nextPage")}
                </Button>
              </div>
            </div>
          )}
        </>
      )}
    </DwScroll>
  )
}
