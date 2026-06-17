"use client"

import { useEffect, useState } from "react"
import type { ApiResponse } from "@/lib/types"
import { handleUnauthorized } from "@/lib/auth-401"

const TOKEN_KEY = "dw.auth.token"

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

    const token = localStorage.getItem(TOKEN_KEY)
    const headers: Record<string, string> = {}
    if (token) headers["Authorization"] = `Bearer ${token}`

    fetch(path, { cache: "no-store", headers })
      .then((res) => {
        if (res.status === 401) {
          handleUnauthorized()
          throw new Error("401 Unauthorized")
        }
        return res.json() as Promise<ApiResponse<T>>
      })
      .then((json) => {
        if (!alive) return
        if (json.code === 0) {
          setState({ data: json.data, loading: false, error: false })
        } else {
          setState({ data: null, loading: false, error: true })
        }
      })
      .catch(() => alive && setState({ data: null, loading: false, error: true }))
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
