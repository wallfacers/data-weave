"use client"

import { useCallback, useEffect, useRef, useState } from "react"

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
    // 关闭旧连接
    if (eventSourceRef.current) {
      eventSourceRef.current.close()
    }

    // 构建 URL（带 Last-Event-ID）
    const fullUrl = lastEventIdRef.current
      ? `${url}${url.includes("?") ? "&" : "?"}lastEventId=${encodeURIComponent(lastEventIdRef.current)}`
      : url

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
    addEventListener("end")

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
