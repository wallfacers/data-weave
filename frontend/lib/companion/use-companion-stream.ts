"use client"

/**
 * 虚拟管家 SSE 流 hook。
 * 直连 SSE_BASE 绕 Next rewrite（代理会缓冲 SSE），
 * 事件集对齐 contracts/companion-api.md：
 *   snapshot / state / report / briefing / message / delta / end
 */
import { useEffect, useRef } from "react"
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

/** SSE_BASE 由构建时注入（Next.js public env），缺省直连 localhost:8000 */
const SSE_BASE = process.env.NEXT_PUBLIC_SSE_BASE ?? "http://localhost:8000"

function buildStreamUrl(): string {
  // SSE 鉴权走 query（EventSource API 不支持自定义 headers，与 070 监督席 SSE 一致；
  // 后端需配合 no-referrer + HTTPS + 审计日志脱敏 query 中的 token）。
  const token =
    typeof window !== "undefined"
      ? localStorage.getItem("dw.auth.token") ?? ""
      : ""
  // projectId 由 X-Project-Id 头在 authFetch 中管理，SSE 走 query
  const projectId =
    typeof window !== "undefined"
      ? localStorage.getItem("dw.auth.projectId") ?? ""
      : ""
  const sp = new URLSearchParams({ token, projectId })
  return `${SSE_BASE}/api/companion/stream?${sp.toString()}`
}

export function useCompanionStream() {
  const esRef = useRef<EventSource | null>(null)
  const store = useCompanionStore

  useEffect(() => {
    const url = buildStreamUrl()
    const es = new EventSource(url)
    esRef.current = es

    store.setState({ connection: "connecting" } as any)

    es.addEventListener("snapshot", (e: MessageEvent) => {
      const data: SnapshotData = JSON.parse(e.data)
      store.setState({ state: data.state } as any)
      store.setState({ briefing: data.briefing } as any)
      store.setState({ reports: data.reports } as any)
      store.setState({ connection: "live" } as any)
    })

    es.addEventListener("state", (e: MessageEvent) => {
      const data: StateEvent = JSON.parse(e.data)
      store.setState({ state: data.state } as any)
    })

    es.addEventListener("report", (e: MessageEvent) => {
      const data: ReportEvent = JSON.parse(e.data)
      if (data.type === "created") {
        store.getState().addReport(data.report)
      } else if (data.type === "closed") {
        store.getState().removeReport(data.report.id)
      }
    })

    es.addEventListener("briefing", (e: MessageEvent) => {
      const data: Briefing = JSON.parse(e.data)
      store.setState({ briefing: data } as any)
    })

    es.addEventListener("message", (e: MessageEvent) => {
      const data: MessageView = JSON.parse(e.data)
      store.getState().addMessage(data)
    })

    es.addEventListener("delta", (e: MessageEvent) => {
      const data: DeltaEvent = JSON.parse(e.data)
      store.getState().appendDelta(data.messageId, data.chunk)
    })

    es.addEventListener("end", (e: MessageEvent) => {
      const data: EndEvent = JSON.parse(e.data)
      store.getState().endMessage(data.messageId, data.interrupted)
    })

    es.onerror = () => {
      store.setState({ connection: "disconnected" } as any)
    }

    return () => {
      es.close()
      esRef.current = null
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return { store }
}
