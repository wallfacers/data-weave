"use client"

/**
 * 实例 DAG 数据 hook：fetch InstanceDagView + SSE 实时状态订阅。
 *
 * GET /api/ops/workflow-instances/{id}/dag → 初始数据
 * SSE /api/ops/workflow-instances/{id}/events/stream → 节点状态实时更新
 */

import { useState, useEffect, useCallback, useRef } from "react"
import { API_BASE, authFetch, type ApiResponse, type InstanceDagView } from "@/lib/types"

/** UUID 格式校验，防止路径遍历注入 */
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
function isUUID(s: string): boolean {
  return UUID_RE.test(s)
}

interface UseInstanceDagResult {
  dag: InstanceDagView | null
  loading: boolean
  error: string | null
  reload: () => void
}

export function useInstanceDag(workflowInstanceId: string | null): UseInstanceDagResult {
  const [dag, setDag] = useState<InstanceDagView | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const sseRef = useRef<EventSource | null>(null)
  const abortRef = useRef<AbortController | null>(null)

  const load = useCallback(async () => {
    if (!workflowInstanceId) return
    if (!isUUID(workflowInstanceId)) {
      setError("Invalid instance ID")
      return
    }
    setLoading(true)
    setError(null)

    // 取消上一次请求
    abortRef.current?.abort()
    abortRef.current = new AbortController()

    try {
      const res = await authFetch(
        `${API_BASE}/api/ops/workflow-instances/${workflowInstanceId}/dag`,
        { signal: abortRef.current.signal },
      )
      const json: ApiResponse<InstanceDagView> = await res.json()
      if (json.code !== 0 || !json.data) {
        setError(json.message || "Failed to load DAG")
        setDag(null)
      } else {
        setDag(json.data)
      }
    } catch (err: unknown) {
      if (err instanceof DOMException && err.name === "AbortError") return
      setError(err instanceof Error ? err.message : "Network error")
    } finally {
      setLoading(false)
    }
  }, [workflowInstanceId])

  // 初始加载
  useEffect(() => {
    load()
  }, [load])

  // SSE 实时状态订阅（端点尚在建设中，失败静默降级）
  useEffect(() => {
    if (!workflowInstanceId) return
    sseRef.current?.close()

    // SSE 端点尚未部署，跳过连接避免 CORS 报错
    // 后端就绪后取消下行注释即可启用实时状态推送
    // const es = new EventSource(
    //   `${SSE_BASE}/api/ops/workflow-instances/${workflowInstanceId}/events/stream`,
    // )
    // sseRef.current = es
    //
    // es.onmessage = (event) => {
    //   try {
    //     const update = JSON.parse(event.data)
    //     if (!update.nodeKey) return
    //     setDag((prev) => {
    //       if (!prev) return prev
    //       return {
    //         ...prev,
    //         nodes: prev.nodes.map((n) =>
    //           n.nodeKey === update.nodeKey ? { ...n, ...update } : n,
    //         ),
    //       }
    //     })
    //   } catch { /* ignore */ }
    // }
    //
    // es.onerror = () => { /* SSE 断连静默 */ }

    return () => {
      // es.close()  // SSE 禁用，无需清理
    }
  }, [workflowInstanceId])

  // 清理
  useEffect(() => {
    return () => {
      abortRef.current?.abort()
      sseRef.current?.close()
    }
  }, [])

  return { dag, loading, error, reload: load }
}
