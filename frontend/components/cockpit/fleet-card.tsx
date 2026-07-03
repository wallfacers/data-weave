import { useTranslations } from "next-intl";
import { HugeiconsIcon } from "@hugeicons/react";
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
} from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { ResourceBar } from "@/components/ui/resource-bar"
import { type WorkerNode } from "@/lib/types";

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
          <ResourceBar label={t("cpu")} value={node.cpu} icon={CpuIcon} />
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
