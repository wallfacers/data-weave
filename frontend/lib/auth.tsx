"use client"

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useState,
  type ReactNode,
} from "react"
import type { ApiResponse } from "@/lib/types"
import { handleUnauthorized } from "@/lib/auth-401"
import { tClient } from "@/lib/i18n-client"

/* ------------------------------------------------------------------ */
/*  Types                                                              */
/* ------------------------------------------------------------------ */

export interface AuthUser {
  userId: number
  tenantId: number
  username: string
  displayName: string
  roles: string[]
  permissions: string[]
  email?: string
  status?: string
}

export interface AuthState {
  user: AuthUser | null
  token: string | null
  loading: boolean
}

export interface AuthContextValue extends AuthState {
  login: (username: string, password: string) => Promise<void>
  logout: () => void
}

/* ------------------------------------------------------------------ */
/*  Context                                                            */
/* ------------------------------------------------------------------ */

const AuthContext = createContext<AuthContextValue | null>(null)

const TOKEN_KEY = "dw.auth.token"
const USER_KEY = "dw.auth.user"

/* ------------------------------------------------------------------ */
/*  Provider                                                           */
/* ------------------------------------------------------------------ */

export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>({
    user: null,
    token: null,
    loading: true,
  })

  // 启动时从 localStorage 恢复
  useEffect(() => {
    try {
      const token = localStorage.getItem(TOKEN_KEY)
      const raw = localStorage.getItem(USER_KEY)
      if (token && raw) {
        const user = JSON.parse(raw) as AuthUser
        setState({ user, token, loading: false })
        return
      }
    } catch {
      // ignore
    }
    setState((s) => ({ ...s, loading: false }))
  }, [])

  const login = useCallback(async (username: string, password: string) => {
    const res = await fetch("/api/auth/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username, password }),
    })
    const json = (await res.json()) as ApiResponse<{
      token: string
      userId: number
      tenantId: number
      username: string
      displayName: string
      roles: string[]
    }>
    if (json.code !== 0 || !json.data) {
      throw new Error(json.message || tClient("auth.loginFailed"))
    }
    const token: string = json.data.token
    const user: AuthUser = {
      userId: json.data.userId,
      tenantId: json.data.tenantId,
      username: json.data.username,
      displayName: json.data.displayName,
      roles: json.data.roles,
      permissions: [],
    }
    localStorage.setItem(TOKEN_KEY, token)
    localStorage.setItem(USER_KEY, JSON.stringify(user))
    setState({ user, token, loading: false })
  }, [])

  const logout = useCallback(() => {
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(USER_KEY)
    setState({ user: null, token: null, loading: false })
  }, [])

  return (
    <AuthContext.Provider value={{ ...state, login, logout }}>
      {children}
    </AuthContext.Provider>
  )
}

/* ------------------------------------------------------------------ */
/*  Hook                                                               */
/* ------------------------------------------------------------------ */

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error("useAuth must be used within AuthProvider")
  return ctx
}

/* ------------------------------------------------------------------ */
/*  Fetch helper — 自动带 Bearer token                                  */
/* ------------------------------------------------------------------ */

export function useApi() {
  const { token } = useAuth()

  return useCallback(
    async (path: string, init?: RequestInit) => {
      const headers: Record<string, string> = {
        "Content-Type": "application/json",
        ...(init?.headers as Record<string, string>),
      }
      if (token) {
        headers["Authorization"] = `Bearer ${token}`
      }
      const res = await fetch(path, { ...init, headers })
      if (res.status === 401) {
        handleUnauthorized()
        throw new Error(tClient("auth.sessionExpired"))
      }
      return res
    },
    [token]
  )
}
