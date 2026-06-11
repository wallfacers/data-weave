"use client"

import { HugeiconsIcon } from "@hugeicons/react"
import { Bug01Icon } from "@hugeicons/core-free-icons"

import { DiagnosisCard } from "@/components/cockpit/diagnosis-card"
import { type TaskDiagnosis } from "@/lib/types"
import { useApi } from "@/lib/workspace/use-api"
import { ViewStatus } from "./view-status"

export function DiagnosisView({ params }: { params?: Record<string, unknown> }) {
  const { data: diagnoses, loading } = useApi<TaskDiagnosis[]>("/api/diagnosis")
  const highlightInstanceId = params?.instanceId != null ? Number(params.instanceId) : null

  if (!diagnoses) return <ViewStatus loading={loading} />

  const sorted = [...diagnoses].sort((a, b) => {
    if (a.status !== b.status) return a.status === "OPEN" ? -1 : 1
    return b.id - a.id
  })
  const ordered = highlightInstanceId
    ? [
        ...sorted.filter((d) => d.taskInstanceId === highlightInstanceId),
        ...sorted.filter((d) => d.taskInstanceId !== highlightInstanceId),
      ]
    : sorted

  return (
    <div className="flex flex-1 flex-col gap-8 overflow-auto p-6 md:p-10">
      <div className="flex flex-col gap-1">
        <div className="flex items-center gap-2">
          <HugeiconsIcon icon={Bug01Icon} className="size-5 text-primary" />
          <h1 className="text-2xl font-semibold tracking-tight">失败诊断</h1>
        </div>
        <p className="text-sm text-muted-foreground">任务失败根因分析与修复建议</p>
      </div>

      {ordered.length === 0 ? (
        <div className="flex flex-1 items-center justify-center p-10 text-center">
          <p className="text-muted-foreground">暂无诊断记录</p>
        </div>
      ) : (
        <div className="flex flex-col gap-4">
          {ordered.map((d) => (
            <DiagnosisCard
              key={d.id}
              diagnosis={d}
              highlighted={
                highlightInstanceId !== null && d.taskInstanceId === highlightInstanceId
              }
            />
          ))}
        </div>
      )}
    </div>
  )
}
