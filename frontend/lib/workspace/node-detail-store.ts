/**
 * DAG 节点详情侧面板状态管理（004-dag-node-detail-panel）。
 *
 * 管理 Ops DAG 弹框中选中的节点及其任务配置详情。
 * 点击节点 → selectNode() 触发详情加载 → setDetail()/setError() 更新视图。
 */
import { create } from "zustand"
import { authFetch, API_BASE } from "@/lib/types"

// ─── Types ────────────────────────────────────────

/** 后端 GET /api/ops/workflows/{id}/nodes/{key}/detail 响应 data 字段 */
export interface NodeTaskDetail {
  nodeKey: string
  taskId: number
  taskName: string
  taskType: string
  versionNo: number
  content: string | null
  paramsJson: string | null
  datasourceId: number | null
  targetDatasourceId: number | null
  timeoutSec: number
  retryMax: number
  publishedAt: string | null
  hasCode: boolean
  deleted: boolean
}

export type PanelLoadState = "idle" | "loading" | "loaded" | "error"

export interface SelectedNode {
  nodeKey: string
  taskId: number
  taskVersionNo?: number | null
}

interface NodeDetailState {
  /** 当前选中的节点（null = 面板关闭） */
  selectedNode: SelectedNode | null
  /** 已获取的节点详情数据 */
  detail: NodeTaskDetail | null
  /** 加载状态 */
  loadState: PanelLoadState
  /** 错误信息 */
  errorMessage: string | null
  /** AbortController 用于取消进行中的请求（连续点击时） */
  _abortController: AbortController | null
  _workflowId: number | null

  // Actions
  selectNode: (workflowId: number, nodeKey: string, taskId: number, taskVersionNo?: number | null) => void
  deselectNode: () => void
  setDetail: (detail: NodeTaskDetail) => void
  setError: (message: string) => void
  retry: () => void
}

export const useNodeDetailStore = create<NodeDetailState>((set, get) => ({
  selectedNode: null,
  detail: null,
  loadState: "idle",
  errorMessage: null,
  _abortController: null,
  _workflowId: null,

  selectNode: (workflowId, nodeKey, taskId, taskVersionNo) => {
    // 取消之前的请求（连续快击保护）
    const prev = get()._abortController
    if (prev) {
      prev.abort()
    }

    const controller = new AbortController()
    set({
      selectedNode: { nodeKey, taskId, taskVersionNo },
      loadState: "loading",
      detail: null,
      errorMessage: null,
      _abortController: controller,
      _workflowId: workflowId,
    })

    authFetch(
      `${API_BASE}/api/ops/workflows/${workflowId}/nodes/${encodeURIComponent(nodeKey)}/detail`,
      { cache: "no-store", signal: controller.signal },
    )
      .then((r) => r.json())
      .then((j) => {
        if (j.code === 0 && j.data) {
          set({ detail: j.data as NodeTaskDetail, loadState: "loaded", errorMessage: null })
        } else {
          set({ loadState: "error", errorMessage: j.message || "Failed to fetch node detail" })
        }
      })
      .catch((e) => {
        if (e instanceof DOMException && e.name === "AbortError") return // 被取消，忽略
        set({ loadState: "error", errorMessage: e.message || "Failed to fetch node detail" })
      })
  },

  deselectNode: () => {
    const prev = get()._abortController
    if (prev) prev.abort()
    set({
      selectedNode: null,
      detail: null,
      loadState: "idle",
      errorMessage: null,
      _abortController: null,
      _workflowId: null,
    })
  },

  setDetail: (detail) => set({ detail, loadState: "loaded", errorMessage: null }),

  setError: (message) => set({ loadState: "error", errorMessage: message }),

  retry: () => {
    const { selectedNode: sn, _workflowId: wfId } = get()
    if (sn && wfId) {
      get().selectNode(wfId, sn.nodeKey, sn.taskId, sn.taskVersionNo)
    }
  },
}))
