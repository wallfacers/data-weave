"use client"

/**
 * 062 US4 从检查点续跑对话框：列出实时任务的检查点，选一个作为回滚恢复点续跑；
 * 无有效检查点时明确告知并仅提供「全量重跑」（FR-008）。
 */

import { useCallback, useEffect, useState } from "react"
import { useTranslations } from "next-intl"
import { toast } from "sonner"

import { Button } from "@/components/ui/button"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { API_BASE, authFetch, type ApiResponse } from "@/lib/types"

interface CheckpointView {
  id: string
  ordinal: number
  status: string
  checkpointPath: string
  completedAt?: string | null
  sizeBytes?: number | null
  expired: boolean
  resumable: boolean
}

export function ResumeCheckpointDialog({
  instanceId,
  taskName,
  open,
  onOpenChange,
  onResumed,
}: {
  instanceId: string | null
  taskName?: string
  open: boolean
  onOpenChange: (open: boolean) => void
  onResumed: () => void
}) {
  const t = useTranslations("streamingTasks")
  const tc = useTranslations("common")
  const [checkpoints, setCheckpoints] = useState<CheckpointView[]>([])
  const [selected, setSelected] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    if (!open || !instanceId) return
    setLoading(true)
    setSelected(null)
    authFetch(`${API_BASE}/api/ops/streaming-tasks/${instanceId}/checkpoints`)
      .then((res) => res.json())
      .then((json: ApiResponse<CheckpointView[]>) => {
        const list = json.code === 0 && Array.isArray(json.data) ? json.data : []
        setCheckpoints(list)
        const firstResumable = list.find((c) => c.resumable)
        setSelected(firstResumable?.id ?? null)
      })
      .catch(() => setCheckpoints([]))
      .finally(() => setLoading(false))
  }, [open, instanceId])

  const hasResumable = checkpoints.some((c) => c.resumable)

  const doResume = useCallback(async () => {
    if (!instanceId || !selected) return
    setBusy(true)
    try {
      const res = await authFetch(`${API_BASE}/api/ops/streaming-tasks/${instanceId}/resume`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ checkpointId: selected }),
      })
      const json = (await res.json().catch(() => null)) as ApiResponse<unknown> | null
      if (!json || json.code !== 0) {
        toast.error(json?.message ?? `HTTP ${res.status}`)
        return
      }
      toast.success(t("actionResume"))
      onOpenChange(false)
      onResumed()
    } finally {
      setBusy(false)
    }
  }, [instanceId, selected, t, onOpenChange, onResumed])

  const doFullRerun = useCallback(async () => {
    if (!instanceId) return
    setBusy(true)
    try {
      const res = await authFetch(`${API_BASE}/api/ops/instances/${instanceId}/rerun`, { method: "POST" })
      const json = (await res.json().catch(() => null)) as ApiResponse<unknown> | null
      if (!json || json.code !== 0) {
        toast.error(json?.message ?? `HTTP ${res.status}`)
        return
      }
      toast.success(t("actionRerunFull"))
      onOpenChange(false)
      onResumed()
    } finally {
      setBusy(false)
    }
  }, [instanceId, t, onOpenChange, onResumed])

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t("resumeTitle")}</DialogTitle>
          <DialogDescription>{taskName ? `${taskName} — ${t("resumeDesc")}` : t("resumeDesc")}</DialogDescription>
        </DialogHeader>

        {loading ? (
          <div className="py-6 text-center text-sm text-muted-foreground">·</div>
        ) : !hasResumable ? (
          <div className="rounded-md bg-muted/40 p-4 text-sm text-muted-foreground">
            {t("resumeNoCheckpoint")}
          </div>
        ) : (
          <div className="flex flex-col gap-2 py-2">
            {checkpoints.map((c) => (
              <label
                key={c.id}
                className={`flex items-center gap-3 rounded-md border p-3 text-sm ${
                  c.resumable ? "cursor-pointer hover:bg-muted/40" : "opacity-50"
                }`}
              >
                <input
                  type="radio"
                  name="checkpoint"
                  value={c.id}
                  disabled={!c.resumable}
                  checked={selected === c.id}
                  onChange={() => setSelected(c.id)}
                />
                <span className="font-medium">{t("checkpointOrdinal", { ordinal: c.ordinal })}</span>
                <span className="font-mono text-xs text-muted-foreground">{c.checkpointPath}</span>
                {c.expired && <span className="text-xs text-muted-foreground">{t("checkpointExpired")}</span>}
              </label>
            ))}
          </div>
        )}

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={busy}>
            {tc("cancel")}
          </Button>
          {hasResumable ? (
            <Button onClick={doResume} disabled={busy || !selected}>
              {t("resumeSubmit")}
            </Button>
          ) : (
            <Button variant="destructive" onClick={doFullRerun} disabled={busy}>
              {t("actionRerunFull")}
            </Button>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
