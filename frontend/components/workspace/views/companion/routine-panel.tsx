"use client"

/**
 * 巡检设置面板（US4）。
 *
 * - 四领域启停开关 + cron 编辑 + 手动触发 + 执行历史
 * - M6：LoadingState 替代 "Loading..."；i18n 全覆盖
 * - M7：cron 编辑输入框 + 触发后 toast 反馈 + 历史刷新
 */
import { useState, useEffect, useCallback } from "react"
import { useTranslations } from "next-intl"
import { toast } from "sonner"
import { Button } from "@/components/ui/button"
import { LoadingState } from "@/components/workspace/shared/loading-state"
import { HugeiconsIcon } from "@hugeicons/react"
import { Cancel01Icon } from "@hugeicons/core-free-icons"
import type { PatrolRoutine, PatrolRun } from "@/lib/companion/types"
import { fetchRoutines, patchRoutine, triggerRoutine, fetchRuns } from "@/lib/companion/api"

interface RoutinePanelProps { open: boolean; onClose: () => void }

export function RoutinePanel({ open, onClose }: RoutinePanelProps) {
  const t = useTranslations("companion")
  const [routines, setRoutines] = useState<PatrolRoutine[]>([])
  const [runs, setRuns] = useState<Record<string, PatrolRun[]>>({})
  const [loading, setLoading] = useState(false)
  const [cronEdits, setCronEdits] = useState<Record<string, string>>({})
  const [triggering, setTriggering] = useState<Record<string, boolean>>({})

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const list = await fetchRoutines()
      setRoutines(list)
      const runsMap: Record<string, PatrolRun[]> = {}
      for (const r of list) {
        try { runsMap[r.id] = await fetchRuns(r.id, 10) }
        catch { runsMap[r.id] = [] }
      }
      setRuns(runsMap)
    } catch { /* API 未就绪 */ }
    finally { setLoading(false) }
  }, [])

  useEffect(() => { if (open) load() }, [open, load])

  const handleToggle = useCallback(async (id: string, enabled: boolean) => {
    try {
      await patchRoutine(id, { enabled })
      setRoutines((prev) => prev.map((r) => (r.id === id ? { ...r, enabled } : r)))
      toast.success(enabled ? t("routine.enabled") : t("routine.disabled"))
    } catch (e) {
      toast.error(e instanceof Error ? e.message : t("chat.sendFailed"))
    }
  }, [t])

  /* M7: cron 编辑 */
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

  /* M7: 触发反馈 + 历史刷新 */
  const handleTrigger = useCallback(async (id: string) => {
    setTriggering((prev) => ({ ...prev, [id]: true }))
    try {
      const { runId } = await triggerRoutine(id)
      toast.success(`Run ${runId} started`)
      // 刷新历史
      const history = await fetchRuns(id, 10)
      setRuns((prev) => ({ ...prev, [id]: history }))
    } catch (e) {
      toast.error(e instanceof Error ? e.message : t("chat.sendFailed"))
    } finally {
      setTriggering((prev) => ({ ...prev, [id]: false }))
    }
  }, [t])

  if (!open) return null

  return (
    <div className="fixed inset-y-0 right-0 z-10 w-[340px] border-l border-border bg-card p-5 shadow-lg">
      <div className="mb-4 flex items-center justify-between">
        <h3 className="text-sm font-semibold">{t("routine.title")}</h3>
        <Button size="icon" variant="ghost" onClick={onClose} aria-label={t("report.close")}>
          <HugeiconsIcon icon={Cancel01Icon} className="size-4" />
        </Button>
      </div>

      {loading ? (
        <LoadingState />
      ) : (
        <div className="flex flex-col gap-4">
          {routines.map((r) => (
            <div key={r.id} className="rounded-[var(--radius)] border border-border/50 p-3">
              <div className="mb-2 flex items-center justify-between">
                <span className="text-[13px] font-medium">{t(`report.domain.${r.domain}`)}</span>
                <Button
                  size="sm"
                  variant={r.enabled ? "default" : "outline"}
                  className="text-[11px]"
                  onClick={() => handleToggle(r.id, !r.enabled)}
                >
                  {r.enabled ? t("routine.enabled") : t("routine.disabled")}
                </Button>
              </div>

              {/* M7: cron 可编辑 */}
              <div className="mb-1 flex items-center gap-1.5 text-[11px] text-muted-foreground">
                <span>{t("routine.cronLabel")}:</span>
                <input
                  className="flex-1 rounded border border-border bg-background px-1.5 py-0.5 text-[11px] text-foreground outline-none focus:border-primary/50"
                  value={cronEdits[r.id] ?? r.cronExpression}
                  onChange={(e) => setCronEdits((prev) => ({ ...prev, [r.id]: e.target.value }))}
                />
                {(cronEdits[r.id] && cronEdits[r.id] !== r.cronExpression) && (
                  <Button size="sm" variant="outline" className="text-[10px] h-5 px-1.5" onClick={() => handleCronSave(r.id)}>
                    {t("common.save")}
                  </Button>
                )}
              </div>

              <Button
                size="sm" variant="outline"
                className="mt-1.5 text-[11px]"
                onClick={() => handleTrigger(r.id)}
                disabled={triggering[r.id]}
              >
                {triggering[r.id] ? t("routine.triggering") : t("routine.trigger")}
              </Button>

              {/* 执行历史 */}
              {runs[r.id]?.length ? (
                <div className="mt-2 border-t border-border/20 pt-2">
                  <div className="mb-1 flex items-center justify-between text-[10px] text-muted-foreground">
                    <span>{t("routine.history")}</span>
                    <Button size="sm" variant="ghost" className="h-4 text-[10px]" onClick={() => fetchRuns(r.id, 10).then((h) => setRuns((prev) => ({ ...prev, [r.id]: h })))}>
                      ↻
                    </Button>
                  </div>
                  {runs[r.id].slice(0, 5).map((run) => {
                    const duration = run.startedAt && run.finishedAt
                      ? `${((new Date(run.finishedAt).getTime() - new Date(run.startedAt).getTime()) / 1000).toFixed(1)}s`
                      : "-"
                    return (
                      <div key={run.id} className="flex items-center gap-2 text-[10px] text-muted-foreground">
                        <span>{run.startedAt}</span>
                        <span>{duration}</span>
                        <span>{run.resultSummary ?? run.state}</span>
                      </div>
                    )
                  })}
                </div>
              ) : (
                <div className="mt-1 text-[10px] text-muted-foreground">{t("routine.noHistory")}</div>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
