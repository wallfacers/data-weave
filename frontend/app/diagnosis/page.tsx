import Link from "next/link";
import { HugeiconsIcon } from "@hugeicons/react";
import {
  Bug01Icon,
  ArrowLeft01Icon,
} from "@hugeicons/core-free-icons";

import { Button } from "@/components/ui/button";
import { DiagnosisCard } from "@/components/cockpit/diagnosis-card";
import { API_BASE, type TaskDiagnosis } from "@/lib/types";

async function getDiagnoses(): Promise<TaskDiagnosis[] | null> {
  try {
    const res = await fetch(`${API_BASE}/api/diagnosis`, { cache: "no-store" });
    if (!res.ok) return null;
    return res.json();
  } catch {
    return null;
  }
}

export default async function DiagnosisPage({
  searchParams,
}: {
  searchParams: Promise<{ instanceId?: string }>;
}) {
  const params = await searchParams;
  const highlightInstanceId = params.instanceId
    ? Number(params.instanceId)
    : null;

  const diagnoses = await getDiagnoses();

  // Sort: OPEN first, then by id desc
  const sorted = diagnoses
    ? [...diagnoses].sort((a, b) => {
        if (a.status !== b.status) {
          return a.status === "OPEN" ? -1 : 1;
        }
        return b.id - a.id;
      })
    : null;

  // Move highlighted item to top if present
  const ordered = sorted
    ? highlightInstanceId
      ? [
          ...sorted.filter((d) => d.taskInstanceId === highlightInstanceId),
          ...sorted.filter((d) => d.taskInstanceId !== highlightInstanceId),
        ]
      : sorted
    : null;

  return (
    <div className="flex flex-1 flex-col gap-8 overflow-auto p-6 md:p-10">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex flex-col gap-1">
          <div className="flex items-center gap-2">
            <HugeiconsIcon
              icon={Bug01Icon}
              className="size-5 text-primary"
            />
            <h1 className="text-2xl font-semibold tracking-tight">失败诊断</h1>
          </div>
          <p className="text-sm text-muted-foreground">
            任务失败根因分析与修复建议
          </p>
        </div>
        <Button
          variant="outline"
          size="sm"
          nativeButton={false}
          render={<Link href="/" />}
        >
          <HugeiconsIcon icon={ArrowLeft01Icon} data-icon="inline-start" />
          返回驾驶舱
        </Button>
      </div>

      {/* Content */}
      {ordered === null ? (
        <div className="flex flex-1 items-center justify-center p-10 text-center">
          <p className="text-muted-foreground">
            无法连接后端（{API_BASE}），请确认后端已启动。
          </p>
        </div>
      ) : ordered.length === 0 ? (
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
                highlightInstanceId !== null &&
                d.taskInstanceId === highlightInstanceId
              }
            />
          ))}
        </div>
      )}
    </div>
  );
}
