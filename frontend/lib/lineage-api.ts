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
export type Confidence = "CONFIRMED" | "UNVERIFIED" | "CONFLICT" | "DECLARED";
export type Transform = "DIRECT" | "EXPRESSION" | "AGGREGATE";
/** 041：边来源通道（旧边可缺省）。 */
export type EdgeSource =
  | "AGENT"
  | "SQL_PARSED"
  | "FORM"
  | "SCRIPT_SQL"
  | "SCRIPT_INFERRED"
  | "SCRIPT_MODEL";

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
  /** 041：来源通道（脚本推断/模型推断边用于视觉区分）。 */
  source?: EdgeSource;
  /** 041：人工裁决态（CONFIRMED；REMOVED 边不出图）。 */
  humanState?: "CONFIRMED";
  /** 041：仅 SCRIPT_MODEL 边——产出模型版本。 */
  modelVersion?: string;
}

/** 041：脚本未解析提示（FR-006）。 */
export interface UnresolvedHintView {
  id: number;
  kind: "DYNAMIC_TABLE" | "DYNAMIC_SQL" | "TIMEOUT" | "PARSE_FAIL";
  scriptHint: string;
  versionNo?: number;
  createdAt?: string;
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

/** 041：人工裁决记录（确认/剔除）。 */
export interface CorrectionView {
  id: number;
  direction: "READ" | "WRITE";
  tableKey: string;
  columnKey?: string;
  status: "CONFIRMED" | "REMOVED";
  operator: string;
  createdAt?: string;
}

/** 041：裁决动作请求。 */
export interface CorrectionRequest {
  action: "CONFIRM" | "REMOVE" | "REVOKE";
  taskDefId: number;
  direction: string;
  tableKey: string;
  columnKey?: string;
}

/** 041：裁决动作结果。 */
export interface CorrectionOutcome {
  outcome: string;
  actionId?: number;
  message?: string;
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

async function post<T>(path: string, body: unknown): Promise<ApiResponse<T>> {
  const url = new URL(path, window.location.origin);
  // 036 FR-013：统一附带当前 projectId（后端按项目作用域校验）
  const pid = currentProjectId();
  if (pid != null) {
    url.searchParams.set("projectId", String(pid));
  }
  const token = localStorage.getItem("dw.auth.token") ?? "";
  const res = await fetch(url.toString(), {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify(body),
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

// ─── 041 脚本血缘：未解析提示 ─────────────────────────────────

/** 某任务的未解析提示（脚本中疑似读写但静态无法确定目标的点）。 */
export function fetchTaskHints(taskDefId: number) {
  return get<UnresolvedHintView[]>(`${BASE}/tasks/${taskDefId}/hints`);
}

// ─── 041 脚本血缘：人工裁决 ───────────────────────────────────

/** 某任务当前生效的裁决记录。 */
export function fetchTaskCorrections(taskDefId: number) {
  return get<CorrectionView[]>(`${BASE}/tasks/${taskDefId}/corrections`);
}

/** 提交裁决（确认/剔除/撤销），写操作走后端统一 gate。 */
export function postCorrection(req: CorrectionRequest) {
  return post<CorrectionOutcome>(`${BASE}/corrections`, req);
}
