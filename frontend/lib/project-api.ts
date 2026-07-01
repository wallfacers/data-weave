"use client"

/**
 * 企业项目 API 客户端 —— 复用既有 GET /api/projects（无新端点）。
 * 服务端按 JWT 的 TenantContext.tenantId() 隔离，仅返回当前租户的项目（宪法 II）。
 *
 * 036-D 新增 GET /api/projects/{id}/me：取当前用户在该项目的角色 + 权限集，供菜单/视图权限过滤。
 */
import { authFetch, type ApiResponse, type ProjectVO } from "@/lib/types"

async function unwrap<T>(res: Response): Promise<T> {
  const json = (await res.json()) as ApiResponse<T>
  if (json.code !== 0) throw new Error(json.message || "API error")
  return json.data as T
}

/** 列出当前租户下的项目（无参形态，租户级）。 */
export async function listProjects(): Promise<ProjectVO[]> {
  const res = await authFetch("/api/projects")
  return unwrap<ProjectVO[]>(res)
}

/**
 * 036-D：当前用户在某项目的成员资格快照（GET /api/projects/{id}/me）。
 * member=false 表示非成员（roleCode=null、permissions=空）。
 */
export interface ProjectMembership {
  member: boolean
  roleCode: string | null
  roleName: string | null
  permissions: string[]
}

/**
 * 036-D：取当前用户在 projectId 的角色/权限集（供左侧导航菜单与视图级权限过滤，FR-041/043）。
 * 对应后端 {@code ProjectRoleService.ProjectMembership}。
 */
export async function getProjectMe(projectId: number): Promise<ProjectMembership> {
  // 带 X-Project-Id：地基 JwtAuthFilter 把 projectId 置入 exchange attributes（ConcurrentHashMap，
  // 不接受 null value）。收尾方修 filter:140 前的兼容绕过；me 用 path id 解析角色，不依赖此头语义。
  const res = await authFetch(`/api/projects/${projectId}/me`, {
    headers: { "X-Project-Id": String(projectId) },
  })
  return unwrap<ProjectMembership>(res)
}

