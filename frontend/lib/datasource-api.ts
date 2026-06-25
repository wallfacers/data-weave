"use client"

import { authFetch, type ApiResponse } from "@/lib/types"
import type {
  DatasourceType,
  DatasourceVO,
  DriverJarVO,
  ConnectionTestResult,
  DatasourceDeleteResult,
  DatasourceCreateRequest,
  DatasourceUpdateRequest,
} from "@/lib/types"
import type { PageResult, FetchQuery, FilterDef, toQueryParams } from "@/lib/data-table"

const API = "" // same-origin

async function unwrap<T>(res: Response): Promise<T> {
  const json = (await res.json()) as ApiResponse<T>
  if (json.code !== 0) throw new Error(json.message || "API error")
  return json.data as T
}

export async function listDatasourceTypes(category?: string): Promise<DatasourceType[]> {
  const params = category ? `?category=${encodeURIComponent(category)}` : ""
  const res = await authFetch(`${API}/api/datasource-types${params}`)
  return unwrap<DatasourceType[]>(res)
}

export async function listDatasources(projectId = 1): Promise<DatasourceVO[]> {
  const res = await authFetch(`${API}/api/datasources?projectId=${projectId}`)
  return unwrap<DatasourceVO[]>(res)
}

/** DataTable server fetcher: 拼接筛选参数 + 分页，调用后端并归一化为 PageResult。 */
export async function fetchDatasources(
  query: FetchQuery,
  defs: FilterDef[],
  projectId: number,
  toQueryParamsFn: typeof import("@/lib/data-table").toQueryParams,
): Promise<PageResult<DatasourceVO>> {
  const qs = toQueryParamsFn(query, defs)
  qs.set("projectId", String(projectId))
  const res = await authFetch(`${API}/api/datasources?${qs.toString()}`)
  const json = (await res.json()) as ApiResponse<unknown>
  if (json.code !== 0) throw new Error(json.message || "API error")
  const data = json.data as Record<string, unknown>
  // 后端返回 PageResult 格式 {items, total, page, size} 或旧版数组
  if (Array.isArray(data)) {
    return { items: data as DatasourceVO[], total: (data as DatasourceVO[]).length, page: 1, size: (data as DatasourceVO[]).length }
  }
  return {
    items: (data.items ?? data.content ?? []) as DatasourceVO[],
    total: (data.total ?? data.totalElements ?? 0) as number,
    page: (data.page ?? (data.number != null ? (data.number as number) + 1 : 1)) as number,
    size: (data.size ?? 20) as number,
  }
}

export async function getDatasource(id: number): Promise<DatasourceVO> {
  const res = await authFetch(`${API}/api/datasources/${id}`)
  return unwrap<DatasourceVO>(res)
}

export async function createDatasource(req: DatasourceCreateRequest): Promise<DatasourceVO> {
  const res = await authFetch(`${API}/api/datasources`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(req),
  })
  return unwrap<DatasourceVO>(res)
}

export async function updateDatasource(
  id: number,
  req: DatasourceUpdateRequest,
): Promise<DatasourceVO> {
  const res = await authFetch(`${API}/api/datasources/${id}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(req),
  })
  return unwrap<DatasourceVO>(res)
}

export async function deleteDatasource(id: number): Promise<DatasourceDeleteResult> {
  const res = await authFetch(`${API}/api/datasources/${id}`, { method: "DELETE" })
  return unwrap<DatasourceDeleteResult>(res)
}

export async function testDatasource(id: number): Promise<ConnectionTestResult> {
  const res = await authFetch(`${API}/api/datasources/${id}/test`, { method: "POST" })
  return unwrap<ConnectionTestResult>(res)
}

export async function testDatasourceConfig(
  req: DatasourceCreateRequest,
  datasourceId?: number,
): Promise<ConnectionTestResult> {
  const params = datasourceId != null ? `?datasourceId=${datasourceId}` : ""
  const res = await authFetch(`${API}/api/datasources/test${params}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(req),
  })
  return unwrap<ConnectionTestResult>(res)
}

// ─── 驱动 jar 资产（datasource-driver-isolation） ─────────

export async function listDriverJars(typeCode: string): Promise<DriverJarVO[]> {
  const res = await authFetch(`${API}/api/driver-jars?typeCode=${encodeURIComponent(typeCode)}`)
  return unwrap<DriverJarVO[]>(res)
}

/** 上传驱动 jar（multipart，不设 Content-Type，浏览器自动加 boundary）。 */
export async function uploadDriverJar(typeCode: string, file: File): Promise<DriverJarVO> {
  const form = new FormData()
  form.append("file", file)
  form.append("typeCode", typeCode)
  const res = await authFetch(`${API}/api/driver-jars`, { method: "POST", body: form })
  return unwrap<DriverJarVO>(res)
}

export async function deleteDriverJar(id: number): Promise<void> {
  await authFetch(`${API}/api/driver-jars/${id}`, { method: "DELETE" })
}

/** 解绑数据源的上传驱动 jar（回退内置默认驱动）。 */
export async function unbindDriverJar(datasourceId: number): Promise<DatasourceVO> {
  const res = await authFetch(`${API}/api/datasources/${datasourceId}/driver-jar`, {
    method: "DELETE",
  })
  return unwrap<DatasourceVO>(res)
}
