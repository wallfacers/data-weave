"use client"

/**
 * 036-D 当前项目权限状态（zustand store + 订阅切项目重算）。
 *
 * 订阅 {@link useProjectContext} 的 currentProjectId：切项目时自动重新拉取「当前用户在该项目的
 * 角色 / 权限集」（GET /api/projects/{id}/me），驱动左侧导航菜单与视图级权限过滤（FR-041/043）。
 *
 * - {@link can}(permission)：当前项目是否持有某权限（菜单/按钮可见性判断）；
 * - {@link currentRoleCode}：当前项目角色 code（ADMIN/DEVELOPER/VIEWER，非成员为 null）；
 * - 未登录 / 未选项目 / 加载中 / 出错：permissions 为空集 → 视图保守按「无写权限」渲染只读入口。
 *
 * 非成员（member=false）视为无任何写权限（只读），与后端 VIEWER 角色「无 role_permission」语义一致。
 */
import { create } from "zustand"

import { useProjectContext } from "@/lib/project-context"
import { getProjectMe, type ProjectMembership } from "@/lib/project-api"

export type ProjectPermissionStatus = "idle" | "loading" | "ready" | "error"

/** 非成员 / 出错的空快照（只读，无任何权限）。 */
export const EMPTY_MEMBERSHIP: ProjectMembership = {
  member: false,
  roleCode: null,
  roleName: null,
  permissions: [],
}

interface ProjectPermissionState {
  /** 当前已加载权限对应的项目 id（与 currentProjectId 同步） */
  projectId: number | null
  membership: ProjectMembership | null
  status: ProjectPermissionStatus
  /** 拉取指定项目的权限（若与当前相同则跳过，避免重复请求） */
  load: (projectId: number) => Promise<void>
  /** 强制重拉当前项目（登录后 / 手动刷新） */
  refresh: () => Promise<void>
}

export const useProjectPermissionsStore = create<ProjectPermissionState>((set, get) => ({
  projectId: null,
  membership: null,
  status: "idle",

  load: async (projectId) => {
    if (projectId == null || projectId <= 0) return
    // 已加载同一项目的权限则跳过（切回原项目无需重拉）
    if (get().projectId === projectId && get().status === "ready") return
    set({ projectId, status: "loading" })
    try {
      const membership = await getProjectMe(projectId)
      set({ projectId, membership, status: "ready" })
    } catch {
      set({ projectId, membership: EMPTY_MEMBERSHIP, status: "error" })
    }
  },

  refresh: async () => {
    const pid = get().projectId ?? useProjectContext.getState().currentProjectId
    if (pid != null && pid > 0) {
      set({ status: "loading" })
      try {
        const membership = await getProjectMe(pid)
        set({ projectId: pid, membership, status: "ready" })
      } catch {
        set({ projectId: pid, membership: EMPTY_MEMBERSHIP, status: "error" })
      }
    }
  },
}))

/**
 * 订阅 projectContext.currentProjectId 变化，自动重算权限（FR-043 切项目重算）。
 * 在 workspace 根组件挂载一次；返回 unsubscribe。
 *
 * 首次挂载若已有 currentProjectId，立即拉一次（覆盖刷新页面后从 localStorage 恢复的项目）。
 */
export function syncProjectPermissions(): () => void {
  const unsub = useProjectContext.subscribe((state, prev) => {
    if (state.currentProjectId !== prev.currentProjectId && state.currentProjectId != null) {
      void useProjectPermissionsStore.getState().load(state.currentProjectId)
    }
  })
  const initial = useProjectContext.getState().currentProjectId
  if (initial != null) void useProjectPermissionsStore.getState().load(initial)
  return unsub
}

/** 当前项目是否持有 permission（菜单/按钮可见性）。loading/error/非成员保守返回 false。 */
export function can(permission: string): boolean {
  const m = useProjectPermissionsStore.getState().membership
  return !!m && m.permissions.includes(permission)
}

/** 当前项目角色 code（ADMIN/DEVELOPER/VIEWER）；非成员/loading 返回 null。 */
export function currentRoleCode(): string | null {
  return useProjectPermissionsStore.getState().membership?.roleCode ?? null
}

/**
 * React hook：订阅当前项目权限，组件级响应切项目重算。
 * can 是绑定当前 membership 的闭包，菜单/按钮直接调用即可。
 */
export function useProjectPermissions(): {
  membership: ProjectMembership | null
  status: ProjectPermissionStatus
  roleCode: string | null
  can: (permission: string) => boolean
} {
  const membership = useProjectPermissionsStore((s) => s.membership)
  const status = useProjectPermissionsStore((s) => s.status)
  const perms = membership?.permissions ?? []
  return {
    membership,
    status,
    roleCode: membership?.roleCode ?? null,
    can: (permission: string) => perms.includes(permission),
  }
}
