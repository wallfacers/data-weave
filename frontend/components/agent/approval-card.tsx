"use client"

import { useState } from "react"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { type ApiResponse, authFetch } from "@/lib/types"

/** 审批单（dataweave.approval CUSTOM 事件负载）。 */
export interface Approval {
  approvalId: number | string
  level?: string
  summary?: string
  message?: string
  requiresConfirmation?: boolean
}

interface ApprovalCardProps {
  approval: Approval
  apiBase: string
  /** 决策完成后回调，msg 为追加给对话的说明文案。 */
  onResolved: (approvalId: number | string, msg: string) => void
}

const LEVEL_TONE: Record<string, string> = {
  L2: "bg-warning/15 text-warning",
  L3: "bg-destructive/10 text-destructive",
}

export function ApprovalCard({ approval, apiBase, onResolved }: ApprovalCardProps) {
  const [confirmation, setConfirmation] = useState("")
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [done, setDone] = useState<string | null>(null)

  async function resolve(action: "approve" | "reject") {
    setBusy(true)
    setError(null)
    try {
      const token = localStorage.getItem("dw.auth.token")
      const headers: Record<string, string> = { "Content-Type": "application/json" }
      if (token) headers["Authorization"] = `Bearer ${token}`

      const res = await authFetch(`${apiBase}/api/approvals/${approval.approvalId}/${action}`, {
        method: "POST",
        headers,
        body: JSON.stringify({ approver: "ui-user", confirmation }),
      })
      const json = await res.json() as ApiResponse<{ success: boolean; message?: string }>
      if (json.code !== 0) {
        setError(json.message ?? "操作失败")
        setBusy(false)
        return
      }
      const data = json.data
      if (!data?.success) {
        setError(data?.message ?? "操作失败")
        setBusy(false)
        return
      }
      const verb = action === "approve" ? "已批准并执行" : "已拒绝"
      setDone(`审批单 #${approval.approvalId} ${verb}：${data.message ?? ""}`)
      onResolved(
        approval.approvalId,
        `审批单 #${approval.approvalId} ${verb}：${data.message ?? ""}`,
      )
    } catch (e) {
      setError(`请求失败：${e instanceof Error ? e.message : String(e)}`)
      setBusy(false)
    }
  }

  if (done) {
    return (
      <div className="rounded-[var(--radius-md)] border bg-muted/40 px-3 py-2 text-xs text-muted-foreground">
        {done}
      </div>
    )
  }

  return (
    <div className="flex flex-col gap-2 rounded-[var(--radius-md)] border bg-card px-3 py-2.5 shadow-sm">
      <div className="flex items-center gap-2">
        <span className="text-sm font-medium">待审批操作</span>
        {approval.level && (
          <Badge className={LEVEL_TONE[approval.level] ?? ""}>{approval.level}</Badge>
        )}
        <span className="text-xs text-muted-foreground">#{approval.approvalId}</span>
      </div>
      {approval.summary && <p className="text-sm">{approval.summary}</p>}
      {approval.message && (
        <p className="text-xs text-muted-foreground">{approval.message}</p>
      )}
      {approval.requiresConfirmation && (
        <Input
          value={confirmation}
          onChange={(e) => setConfirmation(e.target.value)}
          placeholder="不可逆操作：回输目标对象名以二次确认"
          className="h-8 text-xs"
        />
      )}
      {error && <p className="text-xs text-destructive">{error}</p>}
      <div className="flex gap-2">
        <Button size="sm" disabled={busy} onClick={() => resolve("approve")}>
          批准并执行
        </Button>
        <Button
          size="sm"
          variant="ghost"
          disabled={busy}
          onClick={() => resolve("reject")}
        >
          拒绝
        </Button>
      </div>
    </div>
  )
}
