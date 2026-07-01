"use client"

import { useCallback, useEffect, useReducer, useRef, useState } from "react"
import type { ApiResponse } from "@/lib/types"
import { handleUnauthorized } from "@/lib/auth-401"
import { acceptLanguageHeader } from "@/lib/locale-client"
import {
  createRefreshScheduler,
  initialLiveDataState,
  liveDataReducer,
  type LiveDataState,
  type RefreshScheduler,
} from "./refresh-scheduler"

const TOKEN_KEY = "dw.auth.token"

// ─── 常量 ────────────────────────────────────────────────────

/** 统一自动刷新周期（ms），所有纳入视图共用（FR-001/SC-001）。 */
export const LIVE_REFRESH_INTERVAL_MS = 30_000

// ─── 共享取数函数 ────────────────────────────────────────────

/**
 * 纯函数取数：自动带 Bearer token、Accept-Language，no-store，
 * 解包 `ApiResponse<T>`（code===0 返回 data，否则 throw）。
 * `useApi` 复用它，非统计视图继续走 `useApi` 不受影响。
 */
export async function fetchApi<T>(path: string): Promise<T> {
  const token = typeof window !== "undefined" ? localStorage.getItem(TOKEN_KEY) : null
  const headers: Record<string, string> = {
    "Accept-Language": acceptLanguageHeader(),
  }
  if (token) headers["Authorization"] = `Bearer ${token}`

  const res = await fetch(path, { cache: "no-store", headers })
  if (res.status === 401) {
    handleUnauthorized()
    throw new Error("401 Unauthorized")
  }
  const json = (await res.json()) as ApiResponse<T>
  if (json.code === 0) {
    return json.data as T
  }
  throw new Error(json.message ?? `API error code=${json.code}`)
}

// ─── useApi（保持对外签名与「refetch 即清空」语义不变）────────

/** Workspace 视图的客户端取数：no-store，自动带 Bearer token，自动解包 ApiResponse。 */
export function useApi<T>(path: string): ApiState<T> {
  const [state, setState] = useState<ApiState<T>>({
    data: null,
    loading: true,
    error: false,
  })

  useEffect(() => {
    let alive = true
    setState({ data: null, loading: true, error: false })

    fetchApi<T>(path)
      .then((data) => {
        if (!alive) return
        setState({ data, loading: false, error: false })
      })
      .catch(() => {
        if (!alive) return
        setState({ data: null, loading: false, error: true })
      })

    return () => {
      alive = false
    }
  }, [path])

  return state
}

export interface ApiState<T> {
  data: T | null
  loading: boolean
  error: boolean
}

// ─── useRefreshSchedule —— 仅调度 hook（表格视图用）─────────

/**
 * 把 `createRefreshScheduler` 绑定到 React 生命周期：
 * - 用 `useRef` 持 scheduler 与"最新 onTick"。
 * - `active`/`enabled` 变化 → scheduler.update()。
 * - 挂载时监听 `visibilitychange`，卸载时 dispose。
 *
 * 表格视图（freshness / ops 实例面板）的取数由 DataTable 自管，
 * 此 hook 只负责"何时该刷新"——onTick 通常递增 reloadSignal。
 */
export function useRefreshSchedule(
  onTick: () => void | Promise<void>,
  opts: { active?: boolean; enabled?: boolean; intervalMs?: number },
): { tickNow: () => Promise<void> } {
  const { active = false, enabled = true, intervalMs = LIVE_REFRESH_INTERVAL_MS } = opts

  const schedulerRef = useRef<RefreshScheduler | null>(null)
  const onTickRef = useRef(onTick)
  onTickRef.current = onTick
  const intervalRef = useRef(intervalMs)
  intervalRef.current = intervalMs

  // 创建 + 销毁都在挂载 effect 里：
  // ① 创建落到 post-commit，内核 fire-on-create 不会在 render 阶段 dispatch；
  // ② StrictMode/重新挂载会「cleanup→setup」，此处能重建 scheduler（若在 render 创建，
  //    cleanup 置 null 后无法重建 → 自动刷新静默失效）。
  // 创建时用挂载那一刻的 active/enabled（ref 读，避免进依赖）。
  const activeRef = useRef(active)
  activeRef.current = active
  const enabledRef = useRef(enabled)
  enabledRef.current = enabled

  useEffect(() => {
    const initialVisible =
      typeof document === "undefined" ? true : document.visibilityState !== "hidden"
    const s = createRefreshScheduler(() => onTickRef.current(), intervalRef.current, {
      active: activeRef.current,
      enabled: enabledRef.current,
      visible: initialVisible,
    })
    schedulerRef.current = s
    const onVis = () => s.update({ visible: document.visibilityState !== "hidden" })
    document.addEventListener("visibilitychange", onVis)
    return () => {
      document.removeEventListener("visibilitychange", onVis)
      s.dispose()
      schedulerRef.current = null
    }
  }, [])

  // active / enabled 变化推送（scheduler 已存在）
  useEffect(() => {
    schedulerRef.current?.update({ active, enabled })
  }, [active, enabled])

  const tickNow = useCallback(
    () => schedulerRef.current?.tickNow() ?? Promise.resolve(),
    [],
  )
  return { tickNow }
}

// ─── useLiveData —— 生命周期感知的取数 hook ─────────────────

/**
 * 统计视图取数 hook：自动轮询、保留旧数据、失败置 stale。
 *
 * @param source - API path 字符串（内部包成 `() => fetchApi<T>(path)`）
 *                或自定义 `() => Promise<T>`（如 alerts 多端点聚合）。
 * @param opts   - active / enabled / intervalMs / deps
 */
export function useLiveData<T>(
  source: string | (() => Promise<T>),
  opts?: { active?: boolean; enabled?: boolean; intervalMs?: number; deps?: unknown[] },
): LiveDataState<T> & { refresh: () => Promise<void> } {
  const { active = false, enabled = true, intervalMs, deps = [] } = opts ?? {}

  const [state, dispatch] = useReducer(liveDataReducer<T>, undefined, initialLiveDataState<T>)

  // generation：source/deps 变化时自增，丢弃迟到结果
  const genRef = useRef(0)
  const mountedRef = useRef(true)

  // 构建 fetcher
  const fetcherRef = useRef<() => Promise<T>>(
    typeof source === "string" ? () => fetchApi<T>(source) : source,
  )
  useEffect(() => {
    genRef.current++
    fetcherRef.current =
      typeof source === "string" ? () => fetchApi<T>(source) : source
    // source/deps 变化 → 立即重取
    tickNowRef.current()
  }, [typeof source === "string" ? source : undefined, ...deps]) // eslint-disable-line react-hooks/exhaustive-deps

  // onTick：取数 + dispatch
  const tick = useCallback(async () => {
    const gen = ++genRef.current
    dispatch({ type: "start" })
    try {
      const data = await fetcherRef.current()
      if (gen !== genRef.current || !mountedRef.current) return
      dispatch({ type: "success", data, at: Date.now() })
    } catch {
      if (gen !== genRef.current || !mountedRef.current) return
      dispatch({ type: "error" })
    }
  }, [])

  const tickNowRef = useRef<() => Promise<void>>(async () => {})
  const { tickNow } = useRefreshSchedule(tick, { active, enabled, intervalMs })
  tickNowRef.current = tickNow

  useEffect(() => {
    // setup 必须重置为 true：StrictMode/重新挂载会「cleanup→setup」，
    // 若只在 cleanup 置 false 而不在 setup 复位，remount 后 mountedRef 永为 false，
    // tick 的 `!mountedRef.current` 分支永不 dispatch → data 永为 null、loading 卡在 true。
    mountedRef.current = true
    return () => {
      mountedRef.current = false
    }
  }, [])

  return { ...state, refresh: tickNow }
}
