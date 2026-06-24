/** permission part 内联审批（§D4：替代 ApprovalCard 浮层，决策即提交）。 */
"use client"

import { useState } from "react"
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  Cancel01Icon,
  CheckmarkCircle01Icon,
  Loading03Icon,
} from "@hugeicons/core-free-icons"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { useChatStore } from "@/lib/chat/store"
import type { MessagePart } from "@/lib/chat/types"

type PermissionPart = Extract<MessagePart, { type: "permission" }>

export function PermissionPartView({ part }: { part: PermissionPart }) {
  const t = useTranslations("chat")
  const decide = useChatStore((s) => s.decidePermission)
  const [busy, setBusy] = useState(false)
  const resolved = part.status !== "pending"

  async function submit(action: "approve" | "reject") {
    setBusy(true)
    try {
      await decide(part.requestId, action)
    } finally {
      setBusy(false)
    }
  }

  if (resolved) {
    const allowed = part.status === "allowed"
    return (
      <div
        className={`flex items-center gap-2 rounded-[var(--radius-md)] border px-3 py-2 text-xs ${
          allowed
            ? "border-success/30 bg-success/5 text-success"
            : "border-muted bg-muted/40 text-muted-foreground"
        }`}
      >
        <HugeiconsIcon
          icon={allowed ? CheckmarkCircle01Icon : Cancel01Icon}
          className="size-4 shrink-0"
        />
        <span>
          {part.resolvedMessage ?? t(allowed ? "permissionAllowed" : "permissionDenied")}
        </span>
      </div>
    )
  }

  return (
    <div className="flex flex-col gap-2 rounded-[var(--radius-md)] border bg-card px-3 py-2.5 shadow-sm">
      <div className="flex items-center gap-2">
        <span className="text-sm font-medium">{t("permissionTitle")}</span>
        {part.level && (
          <Badge
            className={
              part.level === "L3"
                ? "bg-destructive/10 text-destructive"
                : "bg-warning/15 text-warning"
            }
          >
            {part.level}
          </Badge>
        )}
        <span className="font-mono text-xs text-muted-foreground">
          #{part.requestId}
        </span>
      </div>
      {part.summary && <p className="text-sm">{part.summary}</p>}
      {part.reason && <p className="text-xs text-muted-foreground">{part.reason}</p>}
      <div className="flex gap-2">
        <Button size="sm" disabled={busy} onClick={() => submit("approve")}>
          {busy && (
            <HugeiconsIcon
              icon={Loading03Icon}
              className="animate-spin"
              data-icon="inline-start"
            />
          )}
          {t("approve")}
        </Button>
        <Button
          size="sm"
          variant="ghost"
          disabled={busy}
          onClick={() => submit("reject")}
        >
          {t("reject")}
        </Button>
      </div>
    </div>
  )
}
