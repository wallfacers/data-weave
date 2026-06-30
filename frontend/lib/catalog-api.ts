/**
 * 资产目录 + 指标市场 API 客户端 —— /api/catalog/* + /api/marketplace/*。
 *
 * 统一 ApiResponse<T> 包络；血缘/质量不可达时 data.degraded=true（前端隐藏入口，不报错）。
 * 写经闸门：返回 GateResult（outcome=EXECUTED/PENDING_APPROVAL/REJECTED），调用方按 outcome 分流。
 */

const CATALOG = "/api/catalog";
const MARKET = "/api/marketplace";

// ─── 包络 + 视图类型（镜像后端 record）──────────────────────────

export interface ApiResponse<T> {
  code: number;
  message?: string;
  errorCode?: string | null;
  data: T;
}

export type Sensitivity = "PUBLIC" | "INTERNAL" | "CONFIDENTIAL" | "PII";
export type AssetStatus = "ACTIVE" | "STALE" | "RETIRED";

export interface AssetSummary {
  id: number;
  datasourceId: number;
  qualifiedName: string;
  name?: string;
  ownerId?: number;
  sensitivity: Sensitivity;
  status: AssetStatus;
  tags: string[];
}

export interface SearchResult {
  items: AssetSummary[];
  total: number;
  facets: Record<string, Record<string, number>>;
  truncated: boolean;
}

export interface AssetDetail {
  id: number;
  datasourceId: number;
  qualifiedName: string;
  name?: string;
  description?: string;
  ownerId?: number;
  stewardId?: number;
  glossaryTerms: string[];
  sensitivity: Sensitivity;
  schemaSnapshotJson?: string;
  lineageTableRef?: string;
  status: AssetStatus;
  tags: string[];
  createdAt?: string;
  updatedAt?: string;
}

export interface LineageEntryView {
  available: boolean;
  degraded: boolean;
  degradeReason?: string;
  tableRef?: string;
  upstreamCount: number;
  downstreamCount: number;
}

export interface QualityBadgeView {
  available: boolean;
  degraded: boolean;
  score?: number;
  grade?: string;
}

export interface ListingSummary {
  id: number;
  metricType: string;
  metricId: number;
  metricCode?: string;
  ownerId?: number;
  certification: "NONE" | "CERTIFIED";
  status: string;
  freshnessInfo?: string;
}

export interface ListingSearchResult {
  items: ListingSummary[];
  total: number;
  facets: Record<string, Record<string, number>>;
  truncated: boolean;
}

export interface ListingDetail {
  id: number;
  metricType: string;
  metricId: number;
  metricCode?: string;
  ownerId?: number;
  certification: "NONE" | "CERTIFIED";
  certifiedBy?: number;
  certifiedAt?: string;
  freshnessInfo?: string;
  description?: string;
  status: string;
  definition: Record<string, unknown>;
  reuseCount: number;
}

export interface MarketplaceDetail {
  detail: ListingDetail;
  lineage: LineageEntryView;
}

export type GateOutcome = "EXECUTED" | "PENDING_APPROVAL" | "REJECTED";

export interface GateResult {
  outcome: GateOutcome;
  actionId?: number;
  level?: string;
  message?: string;
  summary?: string;
  requiresConfirmation?: boolean;
}

// ─── fetch 封装 ───────────────────────────────────────────────

function token(): string {
  return (typeof window !== "undefined" && localStorage.getItem("dw.auth.token")) || "";
}

async function get<T>(path: string, params?: Record<string, string | number | undefined>): Promise<ApiResponse<T>> {
  const url = new URL(path, window.location.origin);
  if (params) {
    Object.entries(params).forEach(([k, v]) => {
      if (v !== undefined && v !== null && v !== "") url.searchParams.set(k, String(v));
    });
  }
  const res = await fetch(url.toString(), { headers: { Authorization: `Bearer ${token()}` } });
  return res.json();
}

async function send<T>(method: string, path: string, body?: unknown): Promise<ApiResponse<T>> {
  const res = await fetch(new URL(path, window.location.origin).toString(), {
    method,
    headers: {
      Authorization: `Bearer ${token()}`,
      "Content-Type": "application/json",
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  return res.json();
}

// ─── 资产目录 ─────────────────────────────────────────────────

export interface AssetSearchParams {
  keyword?: string;
  owner?: string;
  tag?: string;
  sensitivity?: string;
  page?: number;
  size?: number;
  projectId?: number;
}

export function searchAssets(p: AssetSearchParams) {
  return get<SearchResult>(`${CATALOG}/assets`, { ...p, projectId: p.projectId ?? 1 });
}

export function fetchAsset(id: number) {
  return get<AssetDetail>(`${CATALOG}/assets/${id}`);
}

export function fetchAssetLineage(id: number, projectId = 1) {
  return get<LineageEntryView>(`${CATALOG}/assets/${id}/lineage`, { projectId });
}

export function fetchAssetQuality(id: number, projectId = 1) {
  return get<QualityBadgeView>(`${CATALOG}/assets/${id}/quality`, { projectId });
}

export function createAsset(body: Record<string, unknown>, projectId = 1) {
  return send<GateResult>("POST", `${CATALOG}/assets?projectId=${projectId}`, body);
}

export function retireAsset(id: number, projectId = 1) {
  return send<GateResult>("DELETE", `${CATALOG}/assets/${id}?projectId=${projectId}`);
}

export function subscribe(body: { targetType: string; targetId: number; changeFilter?: string }, projectId = 1) {
  return send<GateResult>("POST", `${CATALOG}/subscriptions?projectId=${projectId}`, body);
}

// ─── 指标市场 ─────────────────────────────────────────────────

export function searchListings(p: { keyword?: string; certification?: string; page?: number; size?: number; projectId?: number }) {
  return get<ListingSearchResult>(`${MARKET}/metrics`, { ...p, projectId: p.projectId ?? 1 });
}

export function fetchListing(id: number, projectId = 1) {
  return get<MarketplaceDetail>(`${MARKET}/metrics/${id}`, { projectId });
}

export function certifyMetric(id: number, projectId = 1) {
  return send<GateResult>("POST", `${MARKET}/metrics/${id}/certify?projectId=${projectId}`);
}

export function reuseMetric(id: number, body: { consumerType: string; consumerRef: string }, projectId = 1) {
  return send<GateResult>("POST", `${MARKET}/metrics/${id}/reuse?projectId=${projectId}`, body);
}
