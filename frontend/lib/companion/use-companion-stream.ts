"use client"

/**
 * 虚拟管家 SSE 流 hook。
 * 直连 SSE_BASE 绕 Next rewrite（代理会缓冲 SSE，既有硬约定），
 * 事件集对齐 contracts/companion-api.md。
 *
 * 参照 use-incident-stream.ts 骨架：SSE_BASE from @/lib/types、
 * readProjectId() from @/lib/project-header、safeParse、reconnect。
 */
import { useCallback, useEffect, useRef } from "react"
import { SSE_BASE } from "@/lib/types"
import { readProjectId } from "@/lib/project-header"
import { useCompanionStore } from "./store"
import type {
  SnapshotData,
  StateEvent,
  ReportEvent,
  Briefing,
  MessageView,
  DeltaEvent,
  EndEvent,
} from "./types"

function safeParse<T>(raw: string): T | null {
  try { return JSON.parse(raw) as T } catch { return null }
}

export function useCompanionStream() {
  const esRef = useRef<EventSource | null>(null)

  const connect = useCallback(() => {
    if (typeof window === "undefined") return
    if (esRef.current) esRef.current.close()

    const token = localStorage.getItem("dw.auth.token")
    // SSE 鉴权走 query（EventSource API 不支持自定义 headers，与 070 监督席一致）
    let url = `${SSE_BASE}/api/companion/stream?projectId=${readProjectId()}`
    if (token) url += `&token=${encodeURIComponent(token)}`

    const es = new EventSource(url)
    esRef.current = es
    useCompanionStore.getState().setConnection("connecting")

    es.onerror = () => useCompanionStore.getState().setConnection("disconnected")

    es.addEventListener("snapshot", (e: MessageEvent) => {
      const d = safeParse<SnapshotData>(e.data)
      if (!d) return
      useCompanionStore.getState().setCompanionState(d.state)
      useCompanionStore.getState().setBriefing(d.briefing)
      useCompanionStore.getState().setReports(d.reports ?? [])
      useCompanionStore.getState().setConnection("live")
    })

    es.addEventListener("state", (e: MessageEvent) => {
      const d = safeParse<StateEvent>(e.data)
      if (d) useCompanionStore.getState().setCompanionState(d.state)
    })

    es.addEventListener("report", (e: MessageEvent) => {
      const d = safeParse<ReportEvent>(e.data)
      if (!d) return
      if (d.type === "created") useCompanionStore.getState().addReport(d.report)
      else if (d.type === "closed") useCompanionStore.getState().removeReport(d.report.id)
    })

    es.addEventListener("briefing", (e: MessageEvent) => {
      const d = safeParse<Briefing>(e.data)
      if (d) useCompanionStore.getState().setBriefing(d)
    })

    es.addEventListener("message", (e: MessageEvent) => {
      const d = safeParse<MessageView>(e.data)
      if (d) useCompanionStore.getState().addMessage(d)
    })

    es.addEventListener("delta", (e: MessageEvent) => {
      const d = safeParse<DeltaEvent>(e.data)
      if (d) useCompanionStore.getState().appendDelta(d.messageId, d.chunk)
    })

    es.addEventListener("end", (e: MessageEvent) => {
      const d = safeParse<EndEvent>(e.data)
      if (d) useCompanionStore.getState().endMessage(d.messageId, d.interrupted)
    })
  }, [])

  useEffect(() => {
    connect()
    return () => { esRef.current?.close() }
  }, [connect])

  const reconnect = useCallback(() => connect(), [connect])

  return { reconnect }
}
