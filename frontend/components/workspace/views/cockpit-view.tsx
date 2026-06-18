"use client"

import { useTranslations } from "next-intl"
import { HugeiconsIcon, type IconSvgElement } from "@hugeicons/react"
import {
  CheckmarkCircle01Icon,
  Cancel01Icon,
  Loading03Icon,
  ArrowRight01Icon,
  ServerStack01Icon,
  Bug01Icon,
  PlaySquareIcon,
} from "@hugeicons/core-free-icons"

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { Button } from "@/components/ui/button"
import { type DashboardSummary, formatDateTime, truncate } from "@/lib/types"
import { useApi } from "@/lib/workspace/use-api"
import { useWorkspaceStore } from "@/lib/workspace/store"
import { ViewStatus } from "./view-status"
import { DwScroll } from "@/components/ui/dw-scroll"

function StatCard({
  label,
  value,
  icon,
  tone,
}: {
  label: string
  value: number
  icon: IconSvgElement
  tone?: "default" | "success" | "destructive" | "running"
}) {
  const toneClasses = {
    default: "bg-muted text-muted-foreground",
    success: "bg-success/10 text-success",
    destructive: "bg-destructive/10 text-destructive",
    running: "bg-info/10 text-info",
  }

  return (
    <Card>
      <CardContent className="flex items-center gap-4 pt-5">
        <div
          className={`flex size-11 shrink-0 items-center justify-center rounded-xl ${toneClasses[tone ?? "default"]}`}
        >
          <HugeiconsIcon icon={icon} className="size-5" />
        </div>
        <div className="flex flex-col">
          <span className="text-3xl font-semibold tracking-tight font-sans tabular-nums">
            {value}
          </span>
          <span className="text-xs text-muted-foreground">{label}</span>
        </div>
      </CardContent>
    </Card>
  )
}

export function CockpitView() {
  const t = useTranslations("cockpit")
  const { data: summary, loading } = useApi<DashboardSummary>("/api/ops/summary")
  const open = useWorkspaceStore((s) => s.open)

  if (!summary) return <ViewStatus loading={loading} />

  return (
    <DwScroll className="flex-1" innerClassName="flex flex-col gap-8 p-6 md:p-10">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex flex-col gap-1">
          <h1 className="text-2xl font-semibold tracking-tight">{t("title")}</h1>
          <p className="text-sm text-muted-foreground">{t("subtitle")}</p>
        </div>
        <Button variant="outline" size="sm" onClick={() => open("fleet")}>
          <HugeiconsIcon icon={ServerStack01Icon} data-icon="inline-start" />
          {t("clusterMachines")}
        </Button>
      </div>

      {/* Summary stat cards */}
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard label={t("statTotal")} value={summary.total} icon={PlaySquareIcon} />
        <StatCard
          label={t("statSuccess")}
          value={summary.success}
          icon={CheckmarkCircle01Icon}
          tone="success"
        />
        <StatCard
          label={t("statFailed")}
          value={summary.failed}
          icon={Cancel01Icon}
          tone="destructive"
        />
        <StatCard
          label={t("statRunning")}
          value={summary.running}
          icon={Loading03Icon}
          tone="running"
        />
      </div>

      {/* Failed tasks table */}
      <Card>
        <CardHeader>
          <div className="flex items-center justify-between">
            <CardTitle className="flex items-center gap-2">
              <HugeiconsIcon icon={Cancel01Icon} className="size-4 text-destructive" />
              {t("failedTasks")}
              <Badge variant="destructive">{summary.failedInstances.length}</Badge>
            </CardTitle>
          </div>
        </CardHeader>
        <CardContent>
          {summary.failedInstances.length === 0 ? (
            <p className="py-6 text-center text-sm text-muted-foreground">
              {t("noFailedTasks")}
            </p>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="font-sans">{t("colInstance")}</TableHead>
                  <TableHead className="font-sans">{t("colTask")}</TableHead>
                  <TableHead className="font-sans">{t("colNode")}</TableHead>
                  <TableHead className="font-sans">{t("colState")}</TableHead>
                  <TableHead className="font-sans">{t("colFinishedAt")}</TableHead>
                  <TableHead className="font-sans">{t("colLogSummary")}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {summary.failedInstances.map((inst) => (
                  <TableRow key={inst.id}>
                    <TableCell>
                      <button
                        type="button"
                        onClick={() => open("diagnosis", { instanceId: inst.id })}
                        className="font-sans text-link hover:underline"
                      >
                        #{inst.id}
                      </button>
                    </TableCell>
                    <TableCell className="font-sans text-muted-foreground">
                      #{inst.taskId}
                    </TableCell>
                    <TableCell className="font-sans text-muted-foreground">
                      {inst.workerNodeCode ?? "—"}
                    </TableCell>
                    <TableCell>
                      <Badge variant="destructive">{inst.state}</Badge>
                    </TableCell>
                    <TableCell className="font-sans text-muted-foreground">
                      {formatDateTime(inst.finishedAt)}
                    </TableCell>
                    <TableCell className="max-w-[200px] truncate text-muted-foreground">
                      {truncate(inst.log, 50)}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      {/* Agent diagnosing items */}
      <Card>
        <CardHeader>
          <div className="flex items-center justify-between">
            <CardTitle className="flex items-center gap-2">
              <HugeiconsIcon icon={Bug01Icon} className="size-4 text-primary" />
              {t("agentDiagnosing")}
              <Badge variant="secondary">{summary.diagnosing.length}</Badge>
            </CardTitle>
            {summary.diagnosing.length > 0 && (
              <Button variant="ghost" size="sm" onClick={() => open("diagnosis")}>
                {t("viewAll")}
                <HugeiconsIcon
                  icon={ArrowRight01Icon}
                  className="size-3.5"
                  data-icon="inline-end"
                />
              </Button>
            )}
          </div>
        </CardHeader>
        <CardContent>
          {summary.diagnosing.length === 0 ? (
            <p className="py-6 text-center text-sm text-muted-foreground">
              {t("noDiagnosing")}
            </p>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="font-sans">{t("colDiagnosis")}</TableHead>
                  <TableHead>{t("colTitle")}</TableHead>
                  <TableHead className="font-sans">{t("colNode")}</TableHead>
                  <TableHead className="font-sans">{t("colState")}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {summary.diagnosing.map((d) => (
                  <TableRow key={d.id}>
                    <TableCell>
                      <button
                        type="button"
                        onClick={() =>
                          open("diagnosis", { instanceId: d.taskInstanceId })
                        }
                        className="font-sans text-link hover:underline"
                      >
                        #{d.id}
                      </button>
                    </TableCell>
                    <TableCell>{d.title}</TableCell>
                    <TableCell className="font-sans text-muted-foreground">
                      {d.workerNodeCode ?? "—"}
                    </TableCell>
                    <TableCell>
                      <Badge variant={d.status === "RESOLVED" ? "secondary" : "info"}>
                        {d.status === "RESOLVED" ? t("statusResolved") : t("statusDiagnosing")}
                      </Badge>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>
    </DwScroll>
  )
}
