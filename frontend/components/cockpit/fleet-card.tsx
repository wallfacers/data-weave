import { useTranslations } from "next-intl";
import { HugeiconsIcon, type IconSvgElement } from "@hugeicons/react";
import {
  ServerStack01Icon,
  CpuIcon,
  MemoryStickIcon,
  Database01Icon,
  Activity01Icon,
  TaskDaily01Icon,
} from "@hugeicons/core-free-icons";

import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { type WorkerNode } from "@/lib/types";

function ResourceBar({
  label,
  value,
  icon,
}: {
  label: string;
  value: number;
  icon: IconSvgElement;
}) {
  // mem ≥ 90 → destructive; cpu/disk ≥ 90 → destructive; otherwise primary
  const isHigh = value >= 90;
  const pct = Math.min(100, Math.max(0, value));

  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex items-center justify-between text-xs font-sans">
        <span className="flex items-center gap-1.5 text-muted-foreground">
          <HugeiconsIcon icon={icon} className="size-3.5" />
          {label}
        </span>
        <span
          className={isHigh ? "font-medium text-destructive" : "text-foreground"}
        >
          {pct.toFixed(1)}%
        </span>
      </div>
      <div className="h-1.5 w-full overflow-hidden rounded-full bg-muted">
        <div
          className={`h-full rounded-full transition-all ${
            isHigh ? "bg-destructive" : "bg-primary"
          }`}
          style={{ width: `${pct}%` }}
        />
      </div>
    </div>
  );
}

export function FleetCard({ node }: { node: WorkerNode }) {
  const t = useTranslations("fleetCard");
  const isOnline = node.status === "ONLINE";

  return (
    <Card>
      <CardHeader>
        <div className="flex items-start justify-between gap-3">
          <div className="flex items-start gap-3">
            <div
              className={`flex size-9 shrink-0 items-center justify-center rounded-lg ${
                isOnline
                  ? "bg-success/10 text-success"
                  : "bg-muted text-muted-foreground"
              }`}
            >
              <HugeiconsIcon icon={ServerStack01Icon} className="size-4" />
            </div>
            <div className="flex flex-col gap-0.5">
              <CardTitle className="font-sans">{node.nodeCode}</CardTitle>
              <div className="flex items-center gap-2 text-xs text-muted-foreground font-sans">
                <span>{node.host}</span>
                <span>·</span>
                <span>{node.ip}</span>
              </div>
            </div>
          </div>
          <Badge variant={isOnline ? "success" : "secondary"}>
            {isOnline ? t("online") : t("offline")}
          </Badge>
        </div>
      </CardHeader>

      <CardContent className="flex flex-col gap-4">
        {/* Resource bars */}
        <div className="flex flex-col gap-3">
          <ResourceBar label="CPU" value={node.cpu} icon={CpuIcon} />
          <ResourceBar label={t("mem")} value={node.mem} icon={MemoryStickIcon} />
          <ResourceBar label={t("disk")} value={node.disk} icon={Database01Icon} />
        </div>

        {/* Meta info */}
        <div className="grid grid-cols-2 gap-3 rounded-lg bg-muted/50 p-3 font-sans text-xs">
          <div className="flex items-center gap-1.5 text-muted-foreground">
            <HugeiconsIcon icon={Activity01Icon} className="size-3.5" />
            <span>
              {t("load")}{" "}
              <span className="text-foreground">
                {node.loadAvg?.toFixed(2) ?? "—"}
              </span>
            </span>
          </div>
          <div className="flex items-center gap-1.5 text-muted-foreground">
            <HugeiconsIcon icon={TaskDaily01Icon} className="size-3.5" />
            <span>
              {t("runningTasks")}{" "}
              <span className="text-foreground">{node.runningTasks ?? 0}</span>
            </span>
          </div>
          <div className="col-span-2 flex items-center gap-1.5 text-muted-foreground">
            <HugeiconsIcon icon={ServerStack01Icon} className="size-3.5" />
            <span>
              {t("spec")}{" "}
              <span className="text-foreground">{node.capacity || "—"}</span>
            </span>
          </div>
        </div>
      </CardContent>
    </Card>
  );
}
