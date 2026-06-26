"use client"

import { useState } from "react"
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import { BoxIcon } from "@hugeicons/core-free-icons"
import { toast } from "sonner"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { type TaskInstance, API_BASE, authFetch } from "@/lib/types"
import { useFormatDateTime } from "@/hooks/use-format-date-time"
import { useLogPanelStore } from "@/lib/workspace/log-panel-store"

// 活跃态脉冲圆点：继承 Badge 文字色（bg-current），随徽章语义色一起变
function PulseDot() {
  return (
    <span className="relative flex size-1.5">
      <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-current opacity-60" />
      <span className="relative inline-flex size-1.5 rounded-full bg-current" />
    </span>
  )
}

type BadgeVariant = "success" | "info" | "warning" | "destructive" | "outline"

const STATE_BADGE: Record<string, { variant: BadgeVariant; key: string; pulse?: boolean }> = {
  SUCCESS: { variant: "success", key: "stateSuccess" },
  RUNNING: { variant: "success", key: "stateRunning", pulse: true },
  WAIT_RETRY: { variant: "info", key: "stateWaitRetry", pulse: true },
  WAITING: { variant: "warning", key: "stateWaiting" },
  DISPATCHED: { variant: "info", key: "stateDispatched" },
  NOT_RUN: { variant: "outline", key: "stateNotRun" },
  FAILED: { variant: "destructive", key: "stateFailed" },
  KILLED: { variant: "destructive", key: "stateKilled" },
  SKIPPED: { variant: "outline", key: "stateSkipped" },
  PAUSED: { variant: "warning", key: "statePaused" },
  STOPPED: { variant: "destructive", key: "stateStopped" },
}

const ACTION_LABEL_KEY: Record<string, string> = {
  pause: "actionPause",
  resume: "actionResume",
  kill: "actionKill",
  rerun: "actionRerun",
  recover: "actionRecover",
}

export function InstanceTable({ instances }: { instances: TaskInstance[] }) {
  const t = useTranslations("instanceTable")
  const formatDateTime = useFormatDateTime()
  const [, setRefresh] = useState(0)

  function stateBadge(state: string) {
    const cfg = STATE_BADGE[state]
    if (!cfg) {
      return (
        <Badge variant="outline" className="text-muted-foreground">
          {state}
        </Badge>
      )
    }
    const muted = cfg.variant === "outline" ? "text-muted-foreground" : undefined
    return (
      <Badge variant={cfg.variant} className={muted}>
        {cfg.pulse && <PulseDot />}
        {t(cfg.key)}
      </Badge>
    )
  }

  async function doAction(instanceId: number, action: string) {
    const labelKey = ACTION_LABEL_KEY[action]
    const label = labelKey ? t(labelKey) : action
    try {
      const res = await authFetch(`${API_BASE}/api/ops/instances/${instanceId}/${action}`, { method: "POST" })
      const json = await res.json().catch(() => null)
      if (!res.ok || (json && json.code !== 0)) {
        const msg = json?.message ?? `HTTP ${res.status}`
        toast.error(t("actionFailed", { label, msg }))
        return
      }
      toast.success(t("actionSuccess", { label }))
    } catch (e) {
      toast.error(t("actionFailed", { label, msg: e instanceof Error ? e.message : t("networkError") }))
    }
  }

  if (instances.length === 0) {
    return (
      <div className="flex flex-1 flex-col items-center justify-center gap-3 py-20 text-center">
        <div className="flex size-12 items-center justify-center rounded-xl bg-muted text-muted-foreground">
          <HugeiconsIcon icon={BoxIcon} className="size-6" />
        </div>
        <p className="text-sm text-muted-foreground">{t("emptyTitle")}</p>
        <p className="max-w-sm text-xs text-muted-foreground">
          {t("emptyHint")}
        </p>
      </div>
    )
  }

  function handleViewLog(inst: TaskInstance) {
    useLogPanelStore.getState().open(String(inst.id), {
      taskId: inst.taskId,
      startedAt: inst.startedAt,
      finishedAt: inst.finishedAt,
    })
  }

  return (
    <div className="font-sans">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead className="w-24 font-mono">{t("colInstance")}</TableHead>
            <TableHead className="w-20 font-mono">{t("colTask")}</TableHead>
            <TableHead className="w-24">{t("colStatus")}</TableHead>
            <TableHead className="w-28">{t("colNode")}</TableHead>
            <TableHead className="w-40">{t("colStartedAt")}</TableHead>
            <TableHead className="w-40">{t("colFinishedAt")}</TableHead>
            <TableHead className="w-20 text-right">{t("colAttempt")}</TableHead>
            <TableHead className="w-40 text-right">{t("colActions")}</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {instances.map((inst) => (
            <TableRow key={inst.id}>
              <TableCell className="font-mono tabular-nums">{inst.id}</TableCell>
              <TableCell className="font-mono tabular-nums">{inst.taskId}</TableCell>
              <TableCell>
                {stateBadge(inst.state)}
              </TableCell>
              <TableCell className="font-mono text-xs">{inst.workerNodeCode ?? "—"}</TableCell>
              <TableCell className="tabular-nums">{formatDateTime(inst.startedAt)}</TableCell>
              <TableCell className="tabular-nums">{formatDateTime(inst.finishedAt)}</TableCell>
              <TableCell className="text-right tabular-nums">{inst.attempt}</TableCell>
              <TableCell className="text-right">
                <div className="flex justify-end gap-1">
                  {inst.state === "RUNNING" && (
                    <>
                      <Button variant="ghost" size="sm" className="h-6 px-2 text-xs" onClick={() => doAction(inst.id, "pause").then(() => setRefresh(n => n + 1))}>{t("actionPause")}</Button>
                      <Button variant="ghost" size="sm" className="h-6 px-2 text-xs text-destructive" onClick={() => doAction(inst.id, "kill").then(() => setRefresh(n => n + 1))}>{t("actionKill")}</Button>
                    </>
                  )}
                  {inst.state === "PAUSED" && (
                    <>
                      <Button variant="ghost" size="sm" className="h-6 px-2 text-xs" onClick={() => doAction(inst.id, "resume").then(() => setRefresh(n => n + 1))}>{t("actionResume")}</Button>
                      <Button variant="ghost" size="sm" className="h-6 px-2 text-xs text-destructive" onClick={() => doAction(inst.id, "kill").then(() => setRefresh(n => n + 1))}>{t("actionKill")}</Button>
                    </>
                  )}
                  {(inst.state === "SUCCESS" || inst.state === "FAILED") && (
                    <Button variant="ghost" size="sm" className="h-6 px-2 text-xs" onClick={() => doAction(inst.id, "rerun").then(() => setRefresh(n => n + 1))}>{t("actionRerun")}</Button>
                  )}
                  <Button variant="ghost" size="sm" className="h-6 px-2 text-xs" onClick={() => handleViewLog(inst)}>{t("btnLog")}</Button>
                </div>
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  )
}
