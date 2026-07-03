import { API_BASE, authFetch, type ApiResponse } from "@/lib/types"
import { type FetchQuery, type PageResult, toQueryParams } from "@/lib/data-table"
import { type FilterDef } from "@/lib/data-table"

// ── Dashboard ──

export interface FreshnessSummary {
  total: number
  fresh: number
  aging: number
  stale: number
  never: number
}

export interface FreshnessTrend {
  totalDelta: number
  freshDelta: number
  agingDelta: number
  staleDelta: number
}

export interface FreshnessDashboard {
  summary: FreshnessSummary
  trend: FreshnessTrend | null
  snapshotDate: string
}

export async function fetchDashboard(projectId: number): Promise<FreshnessDashboard | null> {
  const res = await authFetch(`${API_BASE}/api/freshness/dashboard?projectId=${projectId}`)
  if (!res.ok) return null
  const json = (await res.json()) as ApiResponse<FreshnessDashboard>
  if (json.code !== 0 || !json.data) return null
  return json.data
}

// ── Table (extended FreshnessRow) ──

export interface FreshnessRow {
  taskId: number
  name: string
  workflowName: string | null
  scheduleType: string | null
  scheduleHuman: string | null
  tier: string
  lastSuccessAt: string | null
  ageHours: number | null
  trend7Days: number[]
}

export async function fetchFreshnessTable(
  query: FetchQuery,
  filters: FilterDef[],
  projectId: number,
  externalTier: string | null,
): Promise<PageResult<FreshnessRow>> {
  const qs = toQueryParams(query, filters)
  // 默认 worst_first
  if (!qs.has("sort")) {
    qs.set("sort", "worst_first")
  }
  qs.set("projectId", String(projectId))
  if (externalTier) {
    qs.set("tiers", externalTier)
  }

  const res = await authFetch(`${API_BASE}/api/freshness?${qs.toString()}`)
  if (!res.ok) return { items: [], total: 0, page: query.page, size: query.size }
  const json = (await res.json()) as ApiResponse<unknown>
  if (json.code !== 0 || !json.data) return { items: [], total: 0, page: query.page, size: query.size }
  const d = json.data as Record<string, unknown>
  if (Array.isArray(d.items)) {
    return {
      items: d.items as FreshnessRow[],
      total: (d.total as number) ?? (d.items as unknown[]).length,
      page: ((d.page as number) ?? 0) + 1,
      size: (d.size as number) ?? query.size,
    }
  }
  return { items: [], total: 0, page: query.page, size: query.size }
}
