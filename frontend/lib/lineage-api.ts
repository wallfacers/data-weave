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
/** 052：探索方向（US1 双向）。 */
export type LineageDirection = "upstream" | "downstream" | "both";
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

/** 052：富属性抽取（GraphNodeView.attrs 开放 map 的强类型视图，US5/FR-019）。 */
export interface LineageNodeAttrs {
  layer?: string;
  /** 产出任务名列表（[(Task)-[:WRITES]->(Table)]）。 */
  producers?: string[];
  /** 今日 synced rows（null = 无同步记录）。 */
  syncedRowsToday?: number | null;
  /** 最近一次同步业务日期（yyyy-MM-dd）。 */
  lastSyncDate?: string;
}

/** 从 GraphNodeView.attrs 安全抽取富属性。 */
export function readNodeAttrs(node: GraphNodeView): LineageNodeAttrs {
  const a = (node.attrs ?? {}) as Partial<LineageNodeAttrs>;
  return {
    layer: node.layer ?? a.layer,
    producers: a.producers,
    syncedRowsToday: a.syncedRowsToday,
    lastSyncDate: a.lastSyncDate,
  };
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
  /** 当前页条数（= downstream.size()）。 */
  nodeCount: number;
  /** 052：真实下游可达总数（独立 COUNT，FR-013）；达 countCap 时 = countCap。 */
  reachableTotal?: number;
  /** 052：达 countCap 时 true → 前端显示「≥N」（FR-013 下限表达）。 */
  totalIsLowerBound?: boolean;
  truncated: boolean;
  truncatedAt?: number;
}

/** 052：按名搜索候选（US2 / FR-008~011）。 */
export interface SearchCandidate {
  id: string;
  type: NodeType;
  name: string;
  /** Table 层（ODS/DWD/…）；Column/Metric 为 null。 */
  layer?: string;
  /** 消歧：Table=datasourceId / Column=tableKey / Metric=metricType。 */
  datasource?: string;
}

/** 052：两点间路径高亮集（US3 / FR-014）。 */
export interface LineagePath {
  from: GraphNodeView;
  to: GraphNodeView;
  /** 所有连接路径上的节点去重集。 */
  nodes: GraphNodeView[];
  /** 路径上的边去重集（供高亮）。 */
  edges: FlowEdgeView[];
  pathExists: boolean;
  truncated: boolean;
}

/** 052：服务端过滤参数（FR-007/019/024，可空；作用 upstream/downstream/impact/neighborhood）。 */
export interface LineageFilters {
  layers?: string[];
  types?: string[];
  confidences?: string[];
  sources?: string[];
}

/** 把过滤参数序列化为 query（逗号连接），空数组省略。 */
function filterParams(f?: LineageFilters): Record<string, string> {
  if (!f) return {};
  const out: Record<string, string> = {};
  (["layers", "types", "confidences", "sources"] as const).forEach((k) => {
    const v = f[k];
    if (v && v.length > 0) out[k] = v.join(",");
  });
  return out;
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
  granularity: Granularity = "TABLE",
  filters?: LineageFilters
) {
  return get<LineageGraph>(`${BASE}/tables/${encodeURIComponent(tableId)}/upstream`, {
    depth: depth ?? 0,
    granularity,
    ...filterParams(filters),
  });
}

/** 表下游（变长路径）。 */
export function fetchDownstream(
  tableId: string,
  depth?: number,
  granularity: Granularity = "TABLE",
  filters?: LineageFilters
) {
  return get<LineageGraph>(`${BASE}/tables/${encodeURIComponent(tableId)}/downstream`, {
    depth: depth ?? 0,
    granularity,
    ...filterParams(filters),
  });
}

/** 052：双向邻域（无向遍历，带真实边，US1/FR-003/007）。 */
export function fetchNeighborhood(
  tableId: string,
  depth = 2,
  granularity: Granularity = "TABLE",
  filters?: LineageFilters
) {
  return get<LineageGraph>(`${BASE}/tables/${encodeURIComponent(tableId)}/neighborhood`, {
    depth,
    granularity,
    ...filterParams(filters),
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
  limit = 100,
  filters?: LineageFilters
) {
  return get<ImpactResult>(`${BASE}/impact/${encodeURIComponent(nodeId)}`, {
    depth: depth ?? 0,
    offset,
    limit,
    ...filterParams(filters),
  });
}

// ─── 052 按名搜索 ──────────────────────────────────────────────

/** 按名搜索数据资产（表/列/指标），空关键字或无匹配 → []（FR-008~011）。 */
export function fetchSearch(
  q: string,
  types?: NodeType[],
  offset = 0,
  limit = 100
) {
  const params: Record<string, string | number> = { q, offset, limit };
  if (types && types.length > 0) params.types = types.join(",");
  return get<SearchCandidate[]>(`${BASE}/search`, params);
}

// ─── 052 两点间路径高亮 ────────────────────────────────────────

/** 两节点间所有连接路径（去重节点∪边高亮集，FR-014）；无路径 pathExists=false。 */
export function fetchPaths(from: string, to: string, depth = 10) {
  return get<LineagePath>(`${BASE}/paths`, {
    from,
    to,
    depth,
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
