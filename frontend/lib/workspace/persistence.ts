"use client"

import { useEffect, useLayoutEffect } from "react"

import { API_BASE, type ApiResponse } from "@/lib/types"
import { useWorkspaceStore, type WorkspaceTab } from "./store"
import { DEFAULT_VIEWS } from "./views"

function isDefaultState(tabs: WorkspaceTab[]): boolean {
  if (tabs.length !== DEFAULT_VIEWS.length) return false
  return DEFAULT_VIEWS.every((view, i) => tabs[i]?.view === view && !tabs[i]?.params)
}
import { handleUnauthorized } from "@/lib/auth-401"

const TOKEN_KEY = "dw.auth.token"
const CONVERSATION_KEY = "dw.conversationId"

/** SSR 安全的 layout effect：浏览器端用 useLayoutEffect（paint 前恢复，零闪烁），服务端退化为 useEffect。 */
const useIsomorphicLayoutEffect =
  typeof window !== "undefined" ? useLayoutEffect : useEffect

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

/** 本地快照缓存 key：与会话 id 绑定，刷新时可同步即时恢复，避免等后端 GET 期间的闪烁。 */
function localSnapshotKey(): string {
  return `dw.workspace.snapshot.${getConversationId()}`
}

const DEBOUNCE_MS = 1000

/**
 * Workspace 快照 ⇄ 后端会话同步：
 * 挂载时 GET 恢复（仅当本地还是纯 Pinned 底座，避免覆盖深链/先手操作；
 * 204/失败/损坏一律回落底座——restore 内部已兜底）；
 * store 变更后防抖 PUT，失败静默（下次变更重试）。
 */
export function useWorkspacePersistence() {
  // 本地快照同步恢复：在浏览器 paint 前从 localStorage 还原上次激活 tab，
  // 消除「先渲染驾驶舱、再跳上次选择」的闪烁。仅当本地仍是纯 Pinned 底座才恢复，
  // 不覆盖深链 / 先手操作。
  useIsomorphicLayoutEffect(() => {
    try {
      const cached = localStorage.getItem(localSnapshotKey())
      if (!cached) return
      const state = useWorkspaceStore.getState()
      if (isDefaultState(state.tabs)) state.restore(cached)
    } catch {
      // localStorage 不可用（隐私模式等）→ 静默退回后端恢复
    }
  }, [])

  useEffect(() => {
    let cancelled = false

    const token = localStorage.getItem(TOKEN_KEY)
    const authHeaders: Record<string, string> = {}
    if (token) authHeaders["Authorization"] = `Bearer ${token}`

    // 后端快照：跨设备 / 本地缓存缺失时的兜底；仅当本地仍是纯 Pinned 底座才覆盖，
    // 避免盖掉上面 layout effect 刚从 localStorage 恢复的态或深链 / 先手操作。
    fetch(workspaceUrl(), { cache: "no-store", headers: authHeaders })
      .then((res) => {
        if (res.status === 401) {
          handleUnauthorized()
          throw new Error("401 Unauthorized")
        }
        return res.json() as Promise<ApiResponse<string>>
      })
      .then((json) => {
        if (cancelled) return
        const text = json.code === 0 ? json.data : null
        if (!text) return
        const state = useWorkspaceStore.getState()
        if (isDefaultState(state.tabs)) state.restore(text)
      })
      .catch(() => {})

    let timer: ReturnType<typeof setTimeout> | null = null
    const unsubscribe = useWorkspaceStore.subscribe(() => {
      const snapshot = useWorkspaceStore.getState().snapshot()
      // 本地即时写：下次刷新可同步恢复（localStorage 同步、量小，不必防抖）
      try {
        localStorage.setItem(localSnapshotKey(), JSON.stringify(snapshot))
      } catch {
        // 忽略本地写失败
      }
      // 后端防抖写
      if (timer) clearTimeout(timer)
      timer = setTimeout(() => {
        fetch(workspaceUrl(), {
          method: "PUT",
          headers: { "Content-Type": "application/json", ...authHeaders },
          body: JSON.stringify(snapshot),
        })
          .then((res) => {
            if (res.status === 401) handleUnauthorized()
          })
          .catch(() => {})
      }, DEBOUNCE_MS)
    })

    return () => {
      cancelled = true
      unsubscribe()
      if (timer) clearTimeout(timer)
    }
  }, [])
}
