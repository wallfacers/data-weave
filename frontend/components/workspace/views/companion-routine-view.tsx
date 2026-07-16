"use client"

/**
 * 巡检设置视图（US4）。
 *
 * 由右侧全高抽屉改造为标准 workspace Tab 视图：卡片风格、圆角、可关闭（TabStrip 承载）。
 * 经 useWorkspaceStore().open("companion-routine") 从管家视图上下文打开（详情视图，不入左导航）。
 * 四领域启停 + cron 编辑 + 手动触发 + 执行历史。
 */
import { useState, useEffect, useCallback } from "react"
import { useTranslations } from "next-intl"
import { toast } from "sonner"
import { HugeiconsIcon } from "@hugeicons/react"
import { RefreshIcon } from "@hugeicons/core-free-icons"
import { Button } from "@/components/ui/button"
import { Card, CardHeader, CardTitle, CardAction, CardContent } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { ViewContainer } from "@/components/ui/view-container"
import { DwScroll } from "@/components/ui/dw-scroll"
import { LoadingState } from "@/components/workspace/shared/loading-state"
import { ViewRefreshControl } from "./view-refresh-control"
import { useFormatDateTime } from "@/hooks/use-format-date-time"
import type { PatrolRoutine, PatrolRun } from "@/lib/companion/types"
import { fetchRoutines, patchRoutine, triggerRoutine, fetchRuns } from "@/lib/companion/api"

export function CompanionRoutineView() {
  const t = useTranslations("companion")
  const tc = useTranslations("common")
  const formatDateTime = useFormatDateTime()
  const [routines, setRoutines] = useState<PatrolRoutine[]>([])
  const [runs, setRuns] = useState<Record<string, PatrolRun[]>>({})
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [stale, setStale] = useState(false)
  const [lastUpdatedAt, setLastUpdatedAt] = useState<number | null>(null)
  const [cronEdits, setCronEdits] = useState<Record<string, string>>({})
  const [triggering, setTriggering] = useState<Record<string, boolean>>({})

  const load = useCallback(async (isRefresh = false) => {
    if (isRefresh) setRefreshing(true); else setLoading(true)
    try {
      const list = await fetchRoutines()
      setRoutines(list)
      const runsMap: Record<string, PatrolRun[]> = {}
      for (const r of list) {
        try { runsMap[r.id] = await fetchRuns(r.id, 10) }
        catch { runsMap[r.id] = [] }
      }
      setRuns(runsMap)
      setStale(false)
      setLastUpdatedAt(Date.now())
    } catch { setStale(true) }
    finally { setLoading(false); setRefreshing(false) }
  }, [])

  useEffect(() => { load() }, [load])

  const handleToggle = useCallback(async (id: string, enabled: boolean) => {
    try {
      await patchRoutine(id, { enabled })
      setRoutines((prev) => prev.map((r) => (r.id === id ? { ...r, enabled } : r)))
      toast.success(enabled ? t("routine.enabled") : t("routine.disabled"))
    } catch (e) {
      toast.error(e instanceof Error ? e.message : t("chat.sendFailed"))
    }
  }, [t])

  const handleCronSave = useCallback(async (id: string) => {
    const cron = cronEdits[id]
    if (!cron) return
    try {
      await patchRoutine(id, { cronExpression: cron })
      setRoutines((prev) => prev.map((r) => (r.id === id ? { ...r, cronExpression: cron } : r)))
      toast.success(t("routine.cronLabel"))
    } catch (e) {
      toast.error(e instanceof Error ? e.message : t("chat.sendFailed"))
    }
  }, [cronEdits, t])

  const handleTrigger = useCallback(async (id: string) => {
    setTriggering((prev) => ({ ...prev, [id]: true }))
    try {
      const { runId } = await triggerRoutine(id)
      toast.success(`Run ${runId} started`)
      const history = await fetchRuns(id, 10)
      setRuns((prev) => ({ ...prev, [id]: history }))
    } catch (e) {
      toast.error(e instanceof Error ? e.message : t("chat.sendFailed"))
    } finally {
      setTriggering((prev) => ({ ...prev, [id]: false }))
    }
  }, [t])

  const refreshHistory = useCallback(async (id: string) => {
    const history = await fetchRuns(id, 10)
    setRuns((prev) => ({ ...prev, [id]: history }))
  }, [])

  return (
    <ViewContainer>
      <div className="shrink-0 flex items-center justify-between pb-3">
        <p className="text-xs text-muted-foreground">{t("routine.subtitle")}</p>
        <ViewRefreshControl
          lastUpdatedAt={lastUpdatedAt}
          refreshing={refreshing}
          stale={stale}
          onRefresh={() => load(true)}
        />
      </div>

      {loading ? (
        <LoadingState />
      ) : (
        <DwScroll className="flex-1" innerClassName="flex flex-col gap-5 min-h-full">
          {routines.map((r) => (
            <Card key={r.id} size="sm">
              <CardHeader>
                <CardTitle>{t(`report.domain.${r.domain}`)}</CardTitle>
                <CardAction>
                  <Button
                    size="sm"
                    variant={r.enabled ? "default" : "outline"}
                    onClick={() => handleToggle(r.id, !r.enabled)}
                  >
                    {r.enabled ? t("routine.enabled") : t("routine.disabled")}
                  </Button>
                </CardAction>
              </CardHeader>

              <CardContent className="flex flex-col gap-3">
                {/* cron 可编辑 */}
                <div className="flex items-center gap-2">
                  <span className="shrink-0 text-xs text-muted-foreground">{t("routine.cronLabel")}</span>
                  <Input
                    className="h-8 flex-1 text-sm"
                    value={cronEdits[r.id] ?? r.cronExpression}
                    onChange={(e) => setCronEdits((prev) => ({ ...prev, [r.id]: e.target.value }))}
                  />
                  {(cronEdits[r.id] && cronEdits[r.id] !== r.cronExpression) && (
                    <Button size="sm" variant="outline" onClick={() => handleCronSave(r.id)}>
                      {tc("save")}
                    </Button>
                  )}
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={() => handleTrigger(r.id)}
                    disabled={triggering[r.id]}
                  >
                    {triggering[r.id] ? t("routine.triggering") : t("routine.trigger")}
                  </Button>
                </div>

                {/* 执行历史 */}
                {runs[r.id]?.length ? (
                  <div className="flex flex-col gap-1.5">
                    <div className="flex items-center justify-between text-xs text-muted-foreground">
                      <span>{t("routine.history")}</span>
                      <Button
                        size="icon"
                        variant="ghost"
                        className="size-6"
                        aria-label={tc("refresh")}
                        onClick={() => refreshHistory(r.id)}
                      >
                        <HugeiconsIcon icon={RefreshIcon} className="size-3.5" />
                      </Button>
                    </div>
                    {runs[r.id].slice(0, 5).map((run) => {
                      const duration = run.startedAt && run.finishedAt
                        ? `${((new Date(run.finishedAt).getTime() - new Date(run.startedAt).getTime()) / 1000).toFixed(1)}s`
                        : "—"
                      return (
                        <div key={run.id} className="flex items-center gap-3 text-xs text-muted-foreground">
                          <span className="shrink-0 tabular-nums">{formatDateTime(run.startedAt)}</span>
                          <span className="shrink-0 tabular-nums">{duration}</span>
                          <span className="truncate">{run.summary ?? run.error ?? run.state}</span>
                        </div>
                      )
                    })}
                  </div>
                ) : (
                  <div className="text-xs text-muted-foreground">{t("routine.noHistory")}</div>
                )}
              </CardContent>
            </Card>
          ))}
        </DwScroll>
      )}
    </ViewContainer>
  )
}
