"use client"

import { useCallback, useEffect, useRef, useState } from "react"

import { SSE_BASE } from "@/lib/types"

/**
 * EventSource 订阅 hook：支持 Last-Event-ID 断线续传、自动重连。
 * 用于实时日志流和状态事件流。
 */
export function useEventSource(url: string): EventSourceState {
  const [state, setState] = useState<EventSourceState>({
    events: [],
    connected: false,
    error: false,
  })
  const lastEventIdRef = useRef<string | null>(null)
  const eventSourceRef = useRef<EventSource | null>(null)

  const connect = useCallback(() => {
    // 空 URL 不建立连接
    if (!url) return

    // 关闭旧连接
    if (eventSourceRef.current) {
      eventSourceRef.current.close()
    }

    // 拼接 JWT token（EventSource 不支持自定义 header，走 query param 兜底）
    const token = typeof window !== "undefined" ? localStorage.getItem("dw.auth.token") : null
    // SSE 直连后端，绕过 Next rewrite 代理对流式响应的缓冲（见 SSE_BASE 注释）。
    // 相对 /api 路径 → 加后端基址；已是绝对 URL 则原样使用。
    let fullUrl = /^https?:\/\//.test(url) ? url : `${SSE_BASE}${url}`
    if (token) {
      fullUrl += `${fullUrl.includes("?") ? "&" : "?"}token=${encodeURIComponent(token)}`
    }
    // 带 Last-Event-ID 断线续传
    if (lastEventIdRef.current) {
      fullUrl += `${fullUrl.includes("?") ? "&" : "?"}lastEventId=${encodeURIComponent(lastEventIdRef.current)}`
    }

    const es = new EventSource(fullUrl)
    eventSourceRef.current = es

    es.onopen = () => {
      setState((s) => ({ ...s, connected: true, error: false }))
    }

    es.onerror = () => {
      setState((s) => ({ ...s, connected: false, error: true }))
      // EventSource 会自动重连，但我们也可以手动控制
    }

    es.onmessage = (event) => {
      // 记录 Last-Event-ID
      if (event.lastEventId) {
        lastEventIdRef.current = event.lastEventId
      }
      setState((s) => ({
        ...s,
        events: [...s.events, { type: "message", data: event.data, id: event.lastEventId }],
      }))
    }

    // 监听自定义事件（log、status、end）
    const addEventListener = (type: string) => {
      es.addEventListener(type, (event: MessageEvent) => {
        if (event.lastEventId) {
          lastEventIdRef.current = event.lastEventId
        }
        setState((s) => ({
          ...s,
          events: [...s.events, { type, data: event.data, id: event.lastEventId }],
        }))
      })
    }

    addEventListener("log")
    addEventListener("status")
    // end：流已结束，主动关闭——否则服务端发完 end 即关连接，浏览器 EventSource 会自动重连并重发全量日志（重复刷屏）。
    es.addEventListener("end", (event: MessageEvent) => {
      setState((s) => ({
        ...s,
        connected: false,
        events: [...s.events, { type: "end", data: event.data, id: event.lastEventId }],
      }))
      es.close()
    })

    return () => {
      es.close()
    }
  }, [url])

  useEffect(() => {
    const cleanup = connect()
    return cleanup
  }, [connect])

  const clearEvents = useCallback(() => {
    setState((s) => ({ ...s, events: [] }))
  }, [])

  return { ...state, clearEvents }
}

export interface EventSourceState {
  events: EventSourceEvent[]
  connected: boolean
  error: boolean
  clearEvents?: () => void
}

export interface EventSourceEvent {
  type: string
  data: string
  id?: string | null
}
