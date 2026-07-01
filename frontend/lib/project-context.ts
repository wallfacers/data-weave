"use client"

/**
 * 当前企业项目上下文（zustand + localStorage 持久化）。
 *
 * 左侧导航的项目切换器读写此 store；按项目维度取数的 api（catalog/datasource）
 * 经 `currentProjectId()` 读取当前项目。初始值同步从 localStorage 读取，首屏无闪烁
 * （仿 lib/date-format-store.ts）。
 *
 * 切换项目时关闭「带参数的非固定标签页」（上下文详情或带项目维度资源参数的入口视图），
 * 保留无参数的功能标签页让其按新项目重新取数（FR-018）。
 */
import { create } from "zustand"

import { listProjects } from "@/lib/project-api"
import type { ProjectVO } from "@/lib/types"
import { useWorkspaceStore } from "@/lib/workspace/store"

const STORAGE_KEY = "dw.project.current"

export type ProjectStatus = "idle" | "loading" | "ready" | "empty" | "error"

interface ProjectContextState {
  currentProjectId: number | null
  projects: ProjectVO[]
  status: ProjectStatus
  loadProjects: () => Promise<void>
  setProject: (id: number) => void
}

/** 从 localStorage 同步读取已选项目 id；SSR/无 localStorage/非法值时回退 null。 */
function readInitialProjectId(): number | null {
  if (typeof window === "undefined") return null
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    if (saved == null) return null
    const n = Number(saved)
    return Number.isFinite(n) && n > 0 ? n : null
  } catch {
    return null
  }
}

function persist(id: number | null) {
  if (typeof window === "undefined") return
  try {
    if (id == null) localStorage.removeItem(STORAGE_KEY)
    else localStorage.setItem(STORAGE_KEY, String(id))
  } catch {
    /* localStorage 不可用（隐私模式等） */
  }
}

export const useProjectContext = create<ProjectContextState>((set, get) => ({
  currentProjectId: readInitialProjectId(),
  projects: [],
  status: "idle",

  loadProjects: async () => {
    set({ status: "loading" })
    try {
      const projects = await listProjects()
      if (projects.length === 0) {
        set({ projects: [], status: "empty" })
        return
      }
      // 持久化值仍有效则保留；否则取列表首个（稳定排序，FR-019）。
      const persisted = get().currentProjectId
      const valid = persisted != null && projects.some((p) => p.id === persisted)
      const currentProjectId = valid ? persisted : projects[0].id
      if (!valid) persist(currentProjectId)
      set({ projects, currentProjectId, status: "ready" })
    } catch {
      set({ status: "error" })
    }
  },

  setProject: (id) => {
    if (get().currentProjectId === id) return
    set({ currentProjectId: id })
    persist(id)
    // 切项目：关闭带参数的非固定标签页（旧项目资源失效），保留无参功能标签让其重取（FR-018）。
    useWorkspaceStore.getState().closeMany((t) => !!t.params)
  },
}))

/**
 * 供按项目取数的 api 默认参数读取当前项目 id；上下文未就绪时回退 1（兼容现状）。
 */
export function currentProjectId(): number {
  return useProjectContext.getState().currentProjectId ?? 1
}
