/**
 * 血缘 API 客户端 —— /api/lineage/* 多粒度端点。
 *
 * 所有端点返回统一的 ApiResponse<T> 包络，调用方负责解包。
 * neo4j 不可达时后端返回 code="lineage.store_unavailable"，前端降级处理。
 *
 * 036 FR-013：所有受隔离请求统一附带当前 projectId（读 useProjectContext），后端按
 * (tenantId, projectId) 作用域查询，杜绝血缘跨项目串号。
 */

import { currentProjectId } from "@/lib/project-context";

const BASE = "/api/lineage";

// ─── 返回视图类型（镜像后端 Java record） ──────────────────────

export type NodeType = "DATASOURCE" | "TABLE" | "COLUMN" | "METRIC";
export type Granularity = "TABLE" | "COLUMN";
export type Confidence = "CONFIRMED" | "UNVERIFIED" | "CONFLICT";
export type Transform = "DIRECT" | "EXPRESSION" | "AGGREGATE";

export interface GraphNodeView {
  id: string;
  type: NodeType;
  name: string;
  layer?: string;
  granularity?: Granularity;
  parentId?: string;
  attrs?: Record<string, unknown>;
}

export interface FlowEdgeView {
  from: string;
  to: string;
  granularity: Granularity;
  taskDefId?: number;
  confidence?: Confidence;
  transform?: Transform;
}

export interface LineageGraph {
  nodes: GraphNodeView[];
  edges: FlowEdgeView[];
  granularity: Granularity;
  depth: number;
  truncated: boolean;
  truncatedAt?: number;
}

export interface ImpactResult {
  root: GraphNodeView;
  downstream: GraphNodeView[];
  edges: FlowEdgeView[];
  nodeCount: number;
  truncated: boolean;
  truncatedAt?: number;
}

export interface MetricLineage {
  metric: GraphNodeView;
  sources: GraphNodeView[];
  edges: FlowEdgeView[];
}

export interface SyncSummary {
  syncedRows: number | null;
}

interface ApiResponse<T> {
  code: string;
  message: string;
  data: T;
}

// ─── fetch 封装 ───────────────────────────────────────────────

async function get<T>(path: string, params?: Record<string, string | number>): Promise<ApiResponse<T>> {
  const url = new URL(path, window.location.origin);
  // 036 FR-013：统一附带当前 projectId（后端按项目作用域查询）
  const pid = currentProjectId();
  if (pid != null) {
    url.searchParams.set("projectId", String(pid));
  }
  if (params) {
    Object.entries(params).forEach(([k, v]) => {
      if (v !== undefined && v !== null && v !== "") {
        url.searchParams.set(k, String(v));
      }
    });
  }
  const token = localStorage.getItem("dw.auth.token") ?? "";
  const res = await fetch(url.toString(), {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!res.ok) {
    throw new Error(`Lineage API ${path} returned ${res.status}`);
  }
  return res.json();
}

// ─── 结构下钻 ──────────────────────────────────────────────────

/** 数据源列表（三级树第一层）。 */
export function fetchDatasources(offset = 0, limit = 100) {
  return get<GraphNodeView[]>(`${BASE}/datasources`, { offset, limit });
}

/** 表的列（三级树第三层）。 */
export function fetchColumns(tableId: string, offset = 0, limit = 100) {
  return get<GraphNodeView[]>(`${BASE}/tables/${encodeURIComponent(tableId)}/columns`, { offset, limit });
}

// ─── 表级上下游 ────────────────────────────────────────────────

/** 表上游（变长路径）。 */
export function fetchUpstream(
  tableId: string,
  depth?: number,
  granularity: Granularity = "TABLE"
) {
  return get<LineageGraph>(`${BASE}/tables/${encodeURIComponent(tableId)}/upstream`, {
    depth: depth ?? 0,
    granularity,
  });
}

/** 表下游（变长路径）。 */
export function fetchDownstream(
  tableId: string,
  depth?: number,
  granularity: Granularity = "TABLE"
) {
  return get<LineageGraph>(`${BASE}/tables/${encodeURIComponent(tableId)}/downstream`, {
    depth: depth ?? 0,
    granularity,
  });
}

// ─── 列级上下游 ────────────────────────────────────────────────

export function fetchColumnUpstream(columnId: string, depth?: number) {
  return get<LineageGraph>(`${BASE}/columns/${encodeURIComponent(columnId)}/upstream`, {
    depth: depth ?? 0,
  });
}

export function fetchColumnDownstream(columnId: string, depth?: number) {
  return get<LineageGraph>(`${BASE}/columns/${encodeURIComponent(columnId)}/downstream`, {
    depth: depth ?? 0,
  });
}

// ─── 影响面 ────────────────────────────────────────────────────

/** 全下游可达集合（表+列）。 */
export function fetchImpact(
  nodeId: string,
  depth?: number,
  offset = 0,
  limit = 100
) {
  return get<ImpactResult>(`${BASE}/impact/${encodeURIComponent(nodeId)}`, {
    depth: depth ?? 0,
    offset,
    limit,
  });
}

// ─── 指标血缘 ──────────────────────────────────────────────────

export function fetchMetricLineage(metricId: string) {
  return get<MetricLineage>(`${BASE}/metrics/${encodeURIComponent(metricId)}/lineage`);
}

// ─── 运行态 ────────────────────────────────────────────────────

/** 今日同步行数（可 null）。 */
export function fetchSyncSummary() {
  return get<SyncSummary>(`${BASE}/sync-summary`);
}
