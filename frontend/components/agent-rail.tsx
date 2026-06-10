"use client"

import { useState } from "react"
import { usePathname, useSearchParams } from "next/navigation"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  AiChat01Icon,
  Cancel01Icon,
} from "@hugeicons/core-free-icons"

import { AgentChat } from "@/components/agent-chat"
import { Button } from "@/components/ui/button"

/** 路由 → 模块名映射，用于标题行上下文展示 */
const MODULE_LABELS: Record<string, string> = {
  "/": "驾驶舱",
  "/tasks": "任务开发",
  "/ops": "调度运维",
  "/metrics": "指标体系",
  "/lineage": "数据血缘",
  "/fleet": "集群机器",
  "/diagnosis": "失败诊断",
  "/integration": "数据集成",
  "/catalog": "资产目录",
  "/quality": "数据质量",
  "/service": "数据服务",
}

/** 从 query 参数中提取上下文对象 */
function useContextObject(searchParams: URLSearchParams): string | null {
  return (
    searchParams.get("instanceId") ??
    searchParams.get("nodeId") ??
    searchParams.get("taskId") ??
    null
  )
}

export function AgentRail() {
  const [open, setOpen] = useState(true)
  const pathname = usePathname()
  const searchParams = useSearchParams()

  const moduleName = MODULE_LABELS[pathname] ?? ""
  const contextObject = useContextObject(searchParams)

  // 收起态：悬浮球
  if (!open) {
    return (
      <div className="flex shrink-0 items-start pt-3 pr-3">
        <Button
          size="icon"
          className="size-10 rounded-full bg-primary text-primary-foreground shadow-md"
          onClick={() => setOpen(true)}
          aria-label="展开 Agent 对话"
        >
          <HugeiconsIcon icon={AiChat01Icon} />
        </Button>
      </div>
    )
  }

  // 展开态：右舷面板
  return (
    <div className="flex w-[420px] shrink-0 flex-col overflow-hidden">
      {/* 标题行：14 高，无下边框（遵守无分割线规则） */}
      <div className="flex h-14 shrink-0 items-center gap-2 px-4">
        <span className="text-sm font-medium">Agent</span>
        {moduleName && (
          <span className="text-xs text-muted-foreground">
            当前：{moduleName}
            {contextObject && ` · ${contextObject}`}
          </span>
        )}
        <div className="flex-1" />
        <Button
          variant="ghost"
          size="icon"
          className="size-7"
          onClick={() => setOpen(false)}
          aria-label="收起 Agent 对话"
        >
          <HugeiconsIcon icon={Cancel01Icon} />
        </Button>
      </div>

      {/* Agent 对话区 */}
      <div className="flex min-h-0 flex-1 flex-col overflow-hidden">
        <AgentChat />
      </div>
    </div>
  )
}
