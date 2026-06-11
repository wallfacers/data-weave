"use client"

import { useEffect, useState } from "react"

import { API_BASE } from "@/lib/types"

export interface ApiState<T> {
  data: T | null
  loading: boolean
  error: boolean
}

/** Workspace 视图的客户端取数：no-store，卸载安全 */
export function useApi<T>(path: string): ApiState<T> {
  const [state, setState] = useState<ApiState<T>>({
    data: null,
    loading: true,
    error: false,
  })

  useEffect(() => {
    let alive = true
    setState({ data: null, loading: true, error: false })
    fetch(`${API_BASE}${path}`, { cache: "no-store" })
      .then((res) => (res.ok ? res.json() : Promise.reject(new Error(String(res.status)))))
      .then((data) => alive && setState({ data, loading: false, error: false }))
      .catch(() => alive && setState({ data: null, loading: false, error: true }))
    return () => {
      alive = false
    }
  }, [path])

  return state
}
