import Link from "next/link"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  AiChat01Icon,
  Analytics01Icon,
  GitBranchIcon,
  SqlIcon,
  WorkflowSquare01Icon,
} from "@hugeicons/core-free-icons"

import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"

const capabilities = [
  {
    icon: SqlIcon,
    title: "Text-to-SQL",
    desc: "用中文提问，Agent 生成 SQL 并执行，返回表格结果。",
  },
  {
    icon: Analytics01Icon,
    title: "指标体系",
    desc: "按名识别指标，返回口径数据并自动溯源，口径不可篡改。",
  },
  {
    icon: WorkflowSquare01Icon,
    title: "任务调度",
    desc: "一句话创建任务、配置 Cron、自动上线。",
  },
  {
    icon: GitBranchIcon,
    title: "数据血缘",
    desc: "回答「指标 → SQL → 物理表」的影响链路。",
  },
]

export default function Page() {
  return (
    <div className="flex flex-1 flex-col gap-8 overflow-auto p-6 md:p-10">
      <div className="flex flex-col gap-4">
        <Badge variant="secondary" className="w-fit">
          MVP
        </Badge>
        <h1 className="text-3xl font-semibold tracking-tight">
          用 Agent 编织数据
        </h1>
        <p className="max-w-2xl text-muted-foreground">
          DataWeave 是 AI Agent 原生的数据中台。用自然语言提出数据需求，Agent
          自主操控任务开发、调度、指标、血缘等全流程操作。
        </p>
        <div className="flex gap-3">
          <Button nativeButton={false} render={<Link href="/agent" />}>
            <HugeiconsIcon icon={AiChat01Icon} data-icon="inline-start" />
            进入 Agent 对话
          </Button>
        </div>
      </div>

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {capabilities.map((c) => (
          <Card key={c.title}>
            <CardHeader>
              <div className="flex size-10 items-center justify-center rounded-lg bg-accent text-accent-foreground">
                <HugeiconsIcon icon={c.icon} />
              </div>
              <CardTitle className="mt-3">{c.title}</CardTitle>
              <CardDescription>{c.desc}</CardDescription>
            </CardHeader>
          </Card>
        ))}
      </div>

      <Card>
        <CardHeader>
          <CardTitle>试一试</CardTitle>
          <CardDescription>
            打开 Agent 对话，输入下面任意一句（已预置种子数据：GMV 指标、orders
            表、每日 GMV 统计任务）：
          </CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-2 text-sm text-muted-foreground">
          <p>· 「GMV 是多少」</p>
          <p>· 「orders 表有多少条」</p>
          <p>· 「创建一个任务，每天 8 点执行 select count(*) from orders，结果存到 report 表」</p>
          <p>· 「GMV 受哪些表影响」</p>
        </CardContent>
      </Card>
    </div>
  )
}
