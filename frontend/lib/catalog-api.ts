/**
 * 资产目录 + 指标市场 API 客户端 —— /api/catalog/* + /api/marketplace/*。
 *
 * 统一 ApiResponse<T> 包络；血缘/质量不可达时 data.degraded=true（前端隐藏入口，不报错）。
 * 写经闸门：返回 GateResult（outcome=EXECUTED/PENDING_APPROVAL/REJECTED），调用方按 outcome 分流。
 */

import { currentProjectId } from "@/lib/project-context";

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

export interface AssetSubscription {
  id: number;
  targetType: string;
  targetId: number;
  changeFilter?: string;
  createdAt?: string;
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
  return parseEnvelope<T>(res);
}

/** 解析响应体；非 JSON（如 403/500 空 body）降级为错误包络,避免 res.json() 抛错破坏调用方。 */
async function parseEnvelope<T>(res: Response): Promise<ApiResponse<T>> {
  try {
    return (await res.json()) as ApiResponse<T>;
  } catch {
    return { code: res.status || -1, message: res.statusText || `HTTP ${res.status}`, errorCode: null, data: null as T };
  }
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
  return parseEnvelope<T>(res);
}

// ─── 资产目录 ─────────────────────────────────────────────────

export interface AssetSearchParams {
  keyword?: string;
  owner?: string;
  tag?: string;
  sensitivity?: string;
  /** 质量分数下限。注：后端 v1 为 no-op（缺 022 评分卡表,不施加过滤）—— 前端透传 + 静态声明。 */
  qualityMin?: number;
  page?: number;
  size?: number;
  projectId?: number;
}

export function searchAssets(p: AssetSearchParams) {
  return get<SearchResult>(`${CATALOG}/assets`, { ...p, projectId: p.projectId ?? currentProjectId() });
}

export function fetchAsset(id: number) {
  return get<AssetDetail>(`${CATALOG}/assets/${id}`);
}

export function fetchAssetLineage(id: number, projectId = currentProjectId()) {
  return get<LineageEntryView>(`${CATALOG}/assets/${id}/lineage`, { projectId });
}

export function fetchAssetQuality(id: number, projectId = currentProjectId()) {
  return get<QualityBadgeView>(`${CATALOG}/assets/${id}/quality`, { projectId });
}

export function createAsset(body: Record<string, unknown>, projectId = currentProjectId()) {
  return send<GateResult>("POST", `${CATALOG}/assets?projectId=${projectId}`, body);
}

export function updateAsset(id: number, patch: Record<string, unknown>, projectId = currentProjectId()) {
  return send<GateResult>("PATCH", `${CATALOG}/assets/${id}?projectId=${projectId}`, patch);
}

export function retireAsset(id: number, projectId = currentProjectId()) {
  return send<GateResult>("DELETE", `${CATALOG}/assets/${id}?projectId=${projectId}`);
}

export function reconcileAsset(id: number, projectId = currentProjectId()) {
  return send<GateResult>("POST", `${CATALOG}/assets/${id}/reconcile?projectId=${projectId}`);
}

export function listSubscriptions() {
  return get<AssetSubscription[]>(`${CATALOG}/subscriptions`);
}

export function subscribe(body: { targetType: string; targetId: number; changeFilter?: string }, projectId = currentProjectId()) {
  return send<GateResult>("POST", `${CATALOG}/subscriptions?projectId=${projectId}`, body);
}

export function unsubscribe(subId: number, projectId = currentProjectId()) {
  return send<GateResult>("DELETE", `${CATALOG}/subscriptions/${subId}?projectId=${projectId}`);
}

// ─── 指标市场 ─────────────────────────────────────────────────

export function searchListings(p: { keyword?: string; certification?: string; page?: number; size?: number; projectId?: number }) {
  return get<ListingSearchResult>(`${MARKET}/metrics`, { ...p, projectId: p.projectId ?? currentProjectId() });
}

export function fetchListing(id: number, projectId = currentProjectId()) {
  return get<MarketplaceDetail>(`${MARKET}/metrics/${id}`, { projectId });
}

export function certifyMetric(id: number, projectId = currentProjectId()) {
  return send<GateResult>("POST", `${MARKET}/metrics/${id}/certify?projectId=${projectId}`);
}

export function reuseMetric(id: number, body: { consumerType: string; consumerRef: string }, projectId = currentProjectId()) {
  return send<GateResult>("POST", `${MARKET}/metrics/${id}/reuse?projectId=${projectId}`, body);
}

/** 指标看板卡片（GET /api/metrics）——上架时选既有指标定义。镜像后端 MetricsController.MetricCard。 */
export interface MetricCardView {
  id: number;
  code: string;
  name: string;
  unit?: string;
  versionNo?: number;
  status?: string;
  value?: unknown;
}

export function fetchMetricCards() {
  // 036 FR-012：附带当前项目，仅返回该项目的指标定义供上架选用
  return get<MetricCardView[]>("/api/metrics", { projectId: currentProjectId() });
}

export function listMetric(
  body: { metricId: number; metricType?: string; metricCode?: string; description?: string; freshnessInfo?: string },
  projectId = currentProjectId(),
) {
  return send<GateResult>("POST", `${MARKET}/metrics?projectId=${projectId}`, body);
}

export function delistMetric(id: number, projectId = currentProjectId()) {
  return send<GateResult>("DELETE", `${MARKET}/metrics/${id}?projectId=${projectId}`);
}
