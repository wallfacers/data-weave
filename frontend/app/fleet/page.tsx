import Link from "next/link";
import { HugeiconsIcon } from "@hugeicons/react";
import {
  ServerStack01Icon,
  ArrowLeft01Icon,
} from "@hugeicons/core-free-icons";

import { Button } from "@/components/ui/button";
import { FleetCard } from "@/components/cockpit/fleet-card";
import { API_BASE, type WorkerNode } from "@/lib/types";

async function getNodes(): Promise<WorkerNode[] | null> {
  try {
    const res = await fetch(`${API_BASE}/api/fleet`, { cache: "no-store" });
    if (!res.ok) return null;
    return res.json();
  } catch {
    return null;
  }
}

export default async function FleetPage() {
  const nodes = await getNodes();

  return (
    <div className="flex flex-1 flex-col gap-8 overflow-auto p-6 md:p-10">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex flex-col gap-1">
          <div className="flex items-center gap-2">
            <HugeiconsIcon
              icon={ServerStack01Icon}
              className="size-5 text-primary"
            />
            <h1 className="text-2xl font-semibold tracking-tight">集群机器</h1>
          </div>
          <p className="text-sm text-muted-foreground">
            Worker 节点状态与资源使用
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
      {nodes === null ? (
        <div className="flex flex-1 items-center justify-center p-10 text-center">
          <p className="text-muted-foreground">
            无法连接后端（{API_BASE}），请确认后端已启动。
          </p>
        </div>
      ) : nodes.length === 0 ? (
        <div className="flex flex-1 items-center justify-center p-10 text-center">
          <p className="text-muted-foreground">暂无注册的 Worker 节点</p>
        </div>
      ) : (
        <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
          {nodes.map((node) => (
            <FleetCard key={node.id} node={node} />
          ))}
        </div>
      )}
    </div>
  );
}
