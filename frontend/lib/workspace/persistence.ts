"use client"

import { useEffect } from "react"

import { API_BASE } from "@/lib/types"
import { useWorkspaceStore } from "./store"

const TOKEN_KEY = "dw.auth.token"
const CONVERSATION_KEY = "dw.conversationId"

/**
 * 对话会话 id：localStorage 持久化，对话（HttpAgent threadId）与 Workspace 快照共用同一 key，
 * 刷新后两者仍指向同一会话。SSR 渲染期返回占位（client 端 useMemo 会以真实 id 重建）。
 */
export function getConversationId(): string {
  if (typeof window === "undefined") return "ssr-placeholder"
  let id = localStorage.getItem(CONVERSATION_KEY)
  if (!id) {
    id = crypto.randomUUID()
    localStorage.setItem(CONVERSATION_KEY, id)
  }
  return id
}

function workspaceUrl(): string {
  return `${API_BASE}/api/agent/sessions/${getConversationId()}/workspace`
}

const DEBOUNCE_MS = 1000

/**
 * Workspace 快照 ⇄ 后端会话同步：
 * 挂载时 GET 恢复（仅当本地还是纯 Pinned 底座，避免覆盖深链/先手操作；
 * 204/失败/损坏一律回落底座——restore 内部已兜底）；
 * store 变更后防抖 PUT，失败静默（下次变更重试）。
 */
export function useWorkspacePersistence() {
  useEffect(() => {
    let cancelled = false

    const token = localStorage.getItem(TOKEN_KEY)
    const authHeaders: Record<string, string> = {}
    if (token) authHeaders["Authorization"] = `Bearer ${token}`

    fetch(workspaceUrl(), { cache: "no-store", headers: authHeaders })
      .then((res) => (res.status === 200 ? res.text() : null))
      .then((text) => {
        if (cancelled || !text) return
        const state = useWorkspaceStore.getState()
        if (state.tabs.every((t) => t.base)) state.restore(text)
      })
      .catch(() => {})

    let timer: ReturnType<typeof setTimeout> | null = null
    const unsubscribe = useWorkspaceStore.subscribe(() => {
      if (timer) clearTimeout(timer)
      timer = setTimeout(() => {
        const snapshot = useWorkspaceStore.getState().snapshot()
        fetch(workspaceUrl(), {
          method: "PUT",
          headers: { "Content-Type": "application/json", ...authHeaders },
          body: JSON.stringify(snapshot),
        }).catch(() => {})
      }, DEBOUNCE_MS)
    })

    return () => {
      cancelled = true
      unsubscribe()
      if (timer) clearTimeout(timer)
    }
  }, [])
}
