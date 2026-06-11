"use client"

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
    success: "bg-primary/10 text-primary",
    destructive: "bg-destructive/10 text-destructive",
    running: "bg-primary/10 text-primary",
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
  const { data: summary, loading } = useApi<DashboardSummary>("/api/ops/summary")
  const open = useWorkspaceStore((s) => s.open)

  if (!summary) return <ViewStatus loading={loading} />

  return (
    <div className="flex flex-1 flex-col gap-8 overflow-auto p-6 md:p-10">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex flex-col gap-1">
          <h1 className="text-2xl font-semibold tracking-tight">驾驶舱</h1>
          <p className="text-sm text-muted-foreground">调度运行全局态势一览</p>
        </div>
        <Button variant="outline" size="sm" onClick={() => open("fleet")}>
          <HugeiconsIcon icon={ServerStack01Icon} data-icon="inline-start" />
          集群机器
        </Button>
      </div>

      {/* Summary stat cards */}
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard label="总运行实例" value={summary.total} icon={PlaySquareIcon} />
        <StatCard
          label="成功"
          value={summary.success}
          icon={CheckmarkCircle01Icon}
          tone="success"
        />
        <StatCard
          label="失败"
          value={summary.failed}
          icon={Cancel01Icon}
          tone="destructive"
        />
        <StatCard
          label="运行中"
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
              失败任务
              <Badge variant="destructive">{summary.failedInstances.length}</Badge>
            </CardTitle>
          </div>
        </CardHeader>
        <CardContent>
          {summary.failedInstances.length === 0 ? (
            <p className="py-6 text-center text-sm text-muted-foreground">
              暂无失败任务
            </p>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="font-sans">实例 #</TableHead>
                  <TableHead className="font-sans">任务 #</TableHead>
                  <TableHead className="font-sans">节点</TableHead>
                  <TableHead className="font-sans">状态</TableHead>
                  <TableHead className="font-sans">结束时间</TableHead>
                  <TableHead className="font-sans">日志摘要</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {summary.failedInstances.map((inst) => (
                  <TableRow key={inst.id}>
                    <TableCell>
                      <button
                        type="button"
                        onClick={() => open("diagnosis", { instanceId: inst.id })}
                        className="font-sans text-primary hover:underline"
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
              Agent 诊断中
              <Badge variant="secondary">{summary.diagnosing.length}</Badge>
            </CardTitle>
            {summary.diagnosing.length > 0 && (
              <Button variant="ghost" size="sm" onClick={() => open("diagnosis")}>
                查看全部
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
              暂无诊断事项
            </p>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="font-sans">诊断 #</TableHead>
                  <TableHead>标题</TableHead>
                  <TableHead className="font-sans">节点</TableHead>
                  <TableHead className="font-sans">状态</TableHead>
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
                        className="font-sans text-primary hover:underline"
                      >
                        #{d.id}
                      </button>
                    </TableCell>
                    <TableCell>{d.title}</TableCell>
                    <TableCell className="font-sans text-muted-foreground">
                      {d.workerNodeCode ?? "—"}
                    </TableCell>
                    <TableCell>
                      <Badge variant={d.status === "RESOLVED" ? "secondary" : "default"}>
                        {d.status === "RESOLVED" ? "已解决" : "诊断中"}
                      </Badge>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>
    </div>
  )
}
