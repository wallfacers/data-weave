"use client"

/**
 * 巡检设置面板（US4）。
 *
 * - 四领域启停开关
 * - cron 编辑
 * - 手动触发按钮
 * - 执行历史列表
 */
import { useState, useEffect, useCallback } from "react"
import { useTranslations } from "next-intl"
import type { PatrolRoutine, PatrolRun } from "@/lib/companion/types"
import { fetchRoutines, patchRoutine, triggerRoutine, fetchRuns } from "@/lib/companion/api"

interface RoutinePanelProps {
  open: boolean
  onClose: () => void
}

export function RoutinePanel({ open, onClose }: RoutinePanelProps) {
  const t = useTranslations("companion")
  const [routines, setRoutines] = useState<PatrolRoutine[]>([])
  const [runs, setRuns] = useState<Record<string, PatrolRun[]>>({})
  const [loading, setLoading] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const list = await fetchRoutines()
      setRoutines(list)
      // 载入各例程的执行历史
      const runsMap: Record<string, PatrolRun[]> = {}
      for (const r of list) {
        try {
          runsMap[r.id] = await fetchRuns(r.id, 10)
        } catch {
          runsMap[r.id] = []
        }
      }
      setRuns(runsMap)
    } catch {
      // API 未就绪，静默
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    if (open) load()
  }, [open, load])

  const handleToggle = useCallback(
    async (id: string, enabled: boolean) => {
      try {
        await patchRoutine(id, { enabled })
        setRoutines((prev) =>
          prev.map((r) => (r.id === id ? { ...r, enabled } : r))
        )
      } catch {
        // silent
      }
    },
    []
  )

  const handleTrigger = useCallback(async (id: string) => {
    try {
      await triggerRoutine(id)
    } catch {
      // silent
    }
  }, [])

  if (!open) return null

  return (
    <div className="fixed inset-y-0 right-0 z-10 w-[340px] border-l border-border bg-card p-5 shadow-lg">
      <div className="mb-4 flex items-center justify-between">
        <h3 className="text-sm font-semibold">{t("routine.title")}</h3>
        <button
          className="rounded-md px-2 py-1 text-muted-foreground hover:text-foreground"
          onClick={onClose}
        >
          ✕
        </button>
      </div>

      {loading ? (
        <div className="text-xs text-muted-foreground">Loading...</div>
      ) : (
        <div className="flex flex-col gap-4">
          {routines.map((r) => (
            <div key={r.id} className="rounded-[var(--radius)] border border-border/50 p-3">
              <div className="mb-2 flex items-center justify-between">
                <span className="text-[13px] font-medium">
                  {t(`report.domain.${r.domain}`)}
                </span>
                <button
                  className={`rounded-full px-2.5 py-0.5 text-[11px] font-medium ${
                    r.enabled
                      ? "bg-success/10 text-success"
                      : "bg-muted text-muted-foreground"
                  }`}
                  onClick={() => handleToggle(r.id, !r.enabled)}
                >
                  {r.enabled ? t("routine.enabled") : t("routine.disabled")}
                </button>
              </div>
              <div className="mb-1 text-[11px] text-muted-foreground">
                {t("routine.cronLabel")}: {r.cronExpression}
              </div>
              <button
                className="mt-1.5 rounded-lg border border-border/50 px-2.5 py-1 text-[11px] text-muted-foreground hover:border-primary/50 hover:text-foreground"
                onClick={() => handleTrigger(r.id)}
              >
                {t("routine.trigger")}
              </button>
              {/* 执行历史 */}
              {runs[r.id]?.length ? (
                <div className="mt-2 border-t border-border/20 pt-2">
                  <div className="mb-1 text-[10px] text-muted-foreground">{t("routine.history")}</div>
                  {runs[r.id].slice(0, 5).map((run) => (
                    <div key={run.id} className="flex items-center gap-2 text-[10px] text-muted-foreground">
                      <span>{run.startedAt}</span>
                      <span>{run.durationMs ? `${(run.durationMs / 1000).toFixed(1)}s` : "-"}</span>
                      <span>{run.resultSummary ?? run.state}</span>
                    </div>
                  ))}
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
