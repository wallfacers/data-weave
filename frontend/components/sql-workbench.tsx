"use client"

import { useState } from "react"
import dynamic from "next/dynamic"
import { HugeiconsIcon } from "@hugeicons/react"
import { Database01Icon, AiChat01Icon } from "@hugeicons/core-free-icons"

// Monaco 仅浏览器可用，dynamic + ssr:false 规避 SSR 求值
const CodeEditor = dynamic(
  () => import("@/components/code-editor").then((m) => m.CodeEditor),
  {
    ssr: false,
    loading: () => (
      <div className="flex h-full items-center justify-center rounded-lg bg-muted text-sm text-muted-foreground">
        加载编辑器…
      </div>
    ),
  },
)

const SAMPLE_SQL = `-- 近 7 日活跃用户分层（指标口径 v3，不可篡改）
WITH active AS (
  SELECT user_id,
         COUNT(*)        AS event_cnt,
         MAX(event_time) AS last_seen
  FROM dwd.user_event
  WHERE dt BETWEEN '2026-06-03' AND '2026-06-10'
    AND event_type IN ('click', 'view', 'purchase')
  GROUP BY user_id
  HAVING COUNT(*) >= 5
)
SELECT
  CASE WHEN event_cnt >= 100 THEN 'L4'
       WHEN event_cnt >= 30  THEN 'L3'
       ELSE 'L2' END        AS tier,
  COUNT(DISTINCT user_id)   AS users,
  ROUND(AVG(event_cnt), 2)  AS avg_events
FROM active
GROUP BY 1
ORDER BY tier DESC;
`

/** tasks 模块的 SQL 编辑器工作台 —— 验证 Monaco + 项目语法主题的真实挂载点 */
export function SqlWorkbench() {
  const [sql, setSql] = useState(SAMPLE_SQL)

  return (
    <div className="flex h-full flex-col gap-3 p-4">
      <div className="flex items-center gap-2">
        <HugeiconsIcon icon={Database01Icon} className="text-primary" />
        <h1 className="text-sm font-medium">SQL 任务编辑器</h1>
        <span className="ml-1 rounded-md bg-muted px-2 py-0.5 font-mono text-xs text-muted-foreground">
          dwd.user_event
        </span>
        <span className="ml-auto inline-flex items-center gap-1 text-xs text-muted-foreground">
          <HugeiconsIcon icon={AiChat01Icon} className="size-4" />
          在右侧 Agent 面板中执行
        </span>
      </div>
      <div className="min-h-0 flex-1">
        <CodeEditor value={sql} onChange={setSql} language="sql" />
      </div>
    </div>
  )
}
