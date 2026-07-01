"use client"

/**
 * 企业项目 API 客户端 —— 复用既有 GET /api/projects（无新端点）。
 * 服务端按 JWT 的 TenantContext.tenantId() 隔离，仅返回当前租户的项目（宪法 II）。
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
