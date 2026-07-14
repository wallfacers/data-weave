"use client"

import { useCallback, useEffect, useReducer, useRef } from "react"

import { SSE_BASE } from "@/lib/types"
import { readProjectId } from "@/lib/project-header"
import {
  initialState,
  reduce,
  type SupervisionAction,
  type SupervisionState,
} from "./store"
import type { Incident, IncidentMessage, IncidentStats } from "./types"

/**
 * 067 指挥中心直播流 hook：直连 SSE_BASE（绕过 Next rewrite 缓冲，既有硬约定），
 * 监听 snapshot/incident/message/briefing/thinking/chip/delta/end 八类事件归约进 store。
 * token/projectId 走 query（EventSource 无自定义 header）；Last-Event-ID 由浏览器自动回传续传。
 */
export function useIncidentStream(): {
  state: SupervisionState
  dispatch: (a: SupervisionAction) => void
  reconnect: () => void
} {
  const [state, dispatch] = useReducer(reduce, undefined, initialState)
  const esRef = useRef<EventSource | null>(null)

  const connect = useCallback(() => {
    if (typeof window === "undefined") return
    if (esRef.current) esRef.current.close()

    const token = localStorage.getItem("dw.auth.token")
    let url = `${SSE_BASE}/api/incidents/stream?projectId=${readProjectId()}`
    if (token) url += `&token=${encodeURIComponent(token)}`

    const es = new EventSource(url)
    esRef.current = es

    es.onopen = () => dispatch({ type: "connected", value: true })
    es.onerror = () => dispatch({ type: "connected", value: false })

    es.addEventListener("snapshot", (e: MessageEvent) => {
      const d = safeParse<{ incidents: Incident[]; briefingStats: IncidentStats }>(e.data)
      if (d) dispatch({ type: "snapshot", incidents: d.incidents ?? [], stats: d.briefingStats ?? null })
    })
    es.addEventListener("incident", (e: MessageEvent) => {
      const inc = safeParse<Incident>(e.data)
      if (inc) dispatch({ type: "incident", incident: inc })
    })
    es.addEventListener("message", (e: MessageEvent) => {
      const d = safeParse<{ incidentId: string; message: IncidentMessage }>(e.data)
      if (d?.message) dispatch({ type: "message", incidentId: d.incidentId, message: d.message })
    })
    es.addEventListener("briefing", (e: MessageEvent) => {
      const d = safeParse<{ summaryLine: string | null; statsJson: string; generatedAt: string | null }>(e.data)
      if (d) {
        dispatch({
          type: "briefing",
          summaryLine: d.summaryLine,
          stats: safeParse<IncidentStats>(d.statsJson) ?? null,
          generatedAt: d.generatedAt,
        })
      }
    })
    es.addEventListener("thinking", (e: MessageEvent) => {
      const d = safeParse<{ incidentId: string; phase: "START" | "STOP"; label: string | null }>(e.data)
      if (d) dispatch({ type: "thinking", incidentId: d.incidentId, phase: d.phase, label: d.label })
    })
    es.addEventListener("chip", (e: MessageEvent) => {
      const d = safeParse<{
        incidentId: string
        chipId: string
        label: string
        status: "RUNNING" | "DONE" | "FAILED"
      }>(e.data)
      if (d) dispatch({ type: "chip", incidentId: d.incidentId, chipId: d.chipId, label: d.label, status: d.status })
    })
    es.addEventListener("delta", (e: MessageEvent) => {
      const d = safeParse<{ incidentId: string; streamId: string; text: string }>(e.data)
      if (d) dispatch({ type: "delta", incidentId: d.incidentId, streamId: d.streamId, text: d.text })
    })
    es.addEventListener("end", () => {
      dispatch({ type: "connected", value: false })
      es.close()
    })
  }, [])

  useEffect(() => {
    connect()
    return () => {
      if (esRef.current) esRef.current.close()
    }
  }, [connect])

  const reconnect = useCallback(() => {
    connect()
  }, [connect])

  return { state, dispatch, reconnect }
}

function safeParse<T>(raw: string): T | null {
  try {
    return JSON.parse(raw) as T
  } catch {
    return null
  }
}
