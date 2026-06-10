import { HugeiconsIcon } from "@hugeicons/react";
import {
  Bug01Icon,
  BulbIcon,
  CheckmarkCircle01Icon,
} from "@hugeicons/core-free-icons";

import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { FixActions } from "@/components/cockpit/fix-actions";
import {
  type TaskDiagnosis,
  type DiagnosisSuggestion,
  safeJsonParse,
} from "@/lib/types";

export function DiagnosisCard({
  diagnosis,
  highlighted,
}: {
  diagnosis: TaskDiagnosis;
  highlighted?: boolean;
}) {
  const isResolved = diagnosis.status === "RESOLVED";
  const context = safeJsonParse<Record<string, unknown>>(diagnosis.contextJson);
  const suggestions =
    safeJsonParse<DiagnosisSuggestion[]>(diagnosis.suggestionsJson) ?? [];

  return (
    <Card
      className={
        highlighted
          ? "ring-2 ring-primary/40"
          : undefined
      }
    >
      <CardHeader>
        <div className="flex items-start justify-between gap-3">
          <div className="flex items-start gap-3">
            <div
              className={`flex size-9 shrink-0 items-center justify-center rounded-lg ${
                isResolved
                  ? "bg-muted text-muted-foreground"
                  : "bg-destructive/10 text-destructive"
              }`}
            >
              <HugeiconsIcon
                icon={isResolved ? CheckmarkCircle01Icon : Bug01Icon}
                className="size-4"
              />
            </div>
            <div className="flex flex-col gap-1">
              <CardTitle
                className={isResolved ? "text-muted-foreground" : undefined}
              >
                {diagnosis.title}
              </CardTitle>
              <div className="flex items-center gap-2 text-xs text-muted-foreground font-sans">
                <span>诊断 #{diagnosis.id}</span>
                {diagnosis.workerNodeCode && (
                  <>
                    <span>·</span>
                    <span>节点 {diagnosis.workerNodeCode}</span>
                  </>
                )}
                <span>·</span>
                <span>任务 #{diagnosis.taskId}</span>
              </div>
            </div>
          </div>
          <Badge variant={isResolved ? "secondary" : "destructive"}>
            {isResolved ? "已解决" : "待处理"}
          </Badge>
        </div>
      </CardHeader>

      <CardContent className="flex flex-col gap-5">
        {/* Root Cause */}
        <div className="flex flex-col gap-2">
          <div className="flex items-center gap-1.5 text-xs font-medium text-muted-foreground uppercase tracking-wide">
            <HugeiconsIcon icon={BulbIcon} className="size-3.5" />
            根因分析
          </div>
          <p
            className={`text-sm leading-relaxed ${
              isResolved ? "text-muted-foreground" : "text-foreground"
            }`}
          >
            {diagnosis.rootCause || "（无根因描述）"}
          </p>
        </div>

        {/* Evidence / Context */}
        {context && Object.keys(context).length > 0 && (
          <div className="flex flex-col gap-2">
            <div className="text-xs font-medium text-muted-foreground uppercase tracking-wide">
              证据
            </div>
            <div className="grid gap-1.5 rounded-lg bg-muted/50 p-3 font-sans text-xs">
              {Object.entries(context).map(([key, value]) => (
                <div key={key} className="flex gap-2">
                  <span className="shrink-0 text-muted-foreground">
                    {key}：
                  </span>
                  <span className="break-all text-foreground">
                    {typeof value === "object"
                      ? JSON.stringify(value)
                      : String(value)}
                  </span>
                </div>
              ))}
            </div>
          </div>
        )}

        {/* Fix Suggestions */}
        {!isResolved && suggestions.length > 0 && (
          <div className="flex flex-col gap-2">
            <div className="text-xs font-medium text-muted-foreground uppercase tracking-wide">
              修复建议
            </div>
            <FixActions
              diagnosisId={diagnosis.id}
              suggestions={suggestions}
            />
          </div>
        )}
      </CardContent>
    </Card>
  );
}
