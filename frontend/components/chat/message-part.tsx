/** 单个 MessagePart 渲染（按 type 分发）。 */
"use client"

import { HugeiconsIcon } from "@hugeicons/react"
import { Alert01Icon } from "@hugeicons/core-free-icons"

import MarkdownContent from "./markdown-content"
import { PermissionPartView } from "./permission-part"
import { ResultTable } from "@/components/agent/result-table"
import type { MessagePart } from "@/lib/chat/types"

export function MessagePartView({
  part,
  streaming,
}: {
  part: MessagePart
  streaming?: boolean
}) {
  switch (part.type) {
    case "text":
      return <MarkdownContent content={part.content} streaming={streaming} />
    case "pending":
      return <PendingDots />
    case "error":
      return (
        <div className="flex items-start gap-2 rounded-[var(--radius-md)] border border-destructive/30 bg-destructive/5 px-3 py-2 text-sm text-destructive">
          <HugeiconsIcon icon={Alert01Icon} className="mt-0.5 size-4 shrink-0" />
          <div className="flex flex-col gap-0.5">
            <span className="text-xs font-medium">{part.code}</span>
            <span className="text-xs opacity-90">{part.message}</span>
          </div>
        </div>
      )
    case "permission":
      return <PermissionPartView part={part} />
    case "result":
      // 内联结果表：不提供关闭（消息流自包含）
      return <ResultTable data={part.data} />
    case "reasoning":
      // 后端 mock agent 当前不发 reasoning；保留渲染，流式时淡显
      return (
        <div
          className={`border-l-2 border-border pl-2 text-xs text-muted-foreground ${
            part.status === "streaming" ? "opacity-70" : ""
          }`}
        >
          {part.text}
        </div>
      )
    case "tool_call":
      return <ToolCallView part={part} />
    default:
      return null
  }
}

/** 首 token 占位的「思考中」三点。 */
function PendingDots() {
  return (
    <div className="flex items-center gap-1 py-1.5">
      <span className="size-1.5 animate-bounce rounded-full bg-muted-foreground/60 [animation-delay:-0.3s]" />
      <span className="size-1.5 animate-bounce rounded-full bg-muted-foreground/60 [animation-delay:-0.15s]" />
      <span className="size-1.5 animate-bounce rounded-full bg-muted-foreground/60" />
    </div>
  )
}

function ToolCallView({
  part,
}: {
  part: Extract<MessagePart, { type: "tool_call" }>
}) {
  return (
    <details className="rounded-[var(--radius-md)] border bg-muted/40 px-2.5 py-1.5 text-xs">
      <summary className="flex cursor-pointer select-none items-center gap-1.5 font-mono">
        <span
          className={`size-1.5 rounded-full ${
            part.status === "running"
              ? "animate-pulse bg-warning"
              : part.status === "error"
                ? "bg-destructive"
                : "bg-success"
          }`}
        />
        {part.name}
      </summary>
      {part.input !== undefined && (
        <pre className="mt-1.5 overflow-x-auto rounded bg-background/60 p-2 text-[11px]">
          {typeof part.input === "string"
            ? part.input
            : JSON.stringify(part.input, null, 2)}
        </pre>
      )}
      {part.output !== undefined && (
        <pre className="mt-1.5 overflow-x-auto rounded bg-background/60 p-2 text-[11px]">
          {typeof part.output === "string"
            ? part.output
            : JSON.stringify(part.output, null, 2)}
        </pre>
      )}
    </details>
  )
}
