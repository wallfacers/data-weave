/**
 * 后端 REST API 类型定义（与 Java domain 对象字段一一对应，camelCase）。
 *
 * 端点：
 *  - GET  /api/ops/summary     → DashboardSummary
 *  - GET  /api/ops/failed      → TaskInstance[]
 *  - GET  /api/fleet           → WorkerNode[]
 *  - GET  /api/fleet/{nodeCode}→ WorkerNode
 *  - GET  /api/diagnosis       → TaskDiagnosis[]
 *  - GET  /api/diagnosis/{id}  → TaskDiagnosis
 *  - POST /api/diagnosis/{id}/fix?action=... → FixResult
 */

export const API_BASE = "";

const AUTH_TOKEN_KEY = "dw.auth.token"

/**
 * 客户端读 cookie `NEXT_LOCALE` → Accept-Language 头值；回退默认中文。
 * 内联以避免循环依赖（locale-client 亦导出同名函数，此处自给自足）。
 */
function acceptLanguage(): string {
  if (typeof document === "undefined") return "zh-CN"
  const match = document.cookie.match(/(?:^|;\s*)NEXT_LOCALE=([^;]+)/)
  const tag = match?.[1]
  return tag === "en-US" || tag === "zh-CN" ? tag : "zh-CN"
}

/** 自动带 Bearer token + Accept-Language 的 fetch 封装。所有调后端 API 的 fetch 应统一走这个。 */
export function authFetch(path: string, init?: RequestInit): Promise<Response> {
  const token = typeof window !== "undefined" ? localStorage.getItem(AUTH_TOKEN_KEY) : null
  const headers: Record<string, string> = {
    "Accept-Language": acceptLanguage(),
    ...(init?.headers as Record<string, string> | undefined),
  }
  if (token) headers["Authorization"] = `Bearer ${token}`
  return fetch(path, { ...init, headers })
}

// ─── 统一响应格式 ─────────────────────────────────────────

/** 后端统一返回格式：{code: number, data: T | null, message: string}。code=0 成功，非零为业务错误码。 */
export interface ApiResponse<T = unknown> {
  code: number;
  data: T | null;
  message: string;
}

// ─── WorkerNode ──────────────────────────────────────────

export interface WorkerNode {
  id: number;
  nodeCode: string;
  host: string;
  ip: string;
  capacity: string; // e.g. "8C/16G"
  cpu: number; // 0–100 percentage
  mem: number;
  disk: number;
  loadAvg: number;
  runningTasks: number;
  status: "ONLINE" | "OFFLINE";
  lastHeartbeat: string; // ISO datetime
}

// ─── TaskInstance ────────────────────────────────────────

export type TaskState =
  | "SUCCESS"
  | "FAILED"
  | "RUNNING"
  | "WAITING"
  | "NOT_RUN"
  | "STOPPED"
  | "PAUSED";

export interface TaskInstance {
  id: number;
  taskId: number;
  workflowInstanceId: number | null;
  runMode: string;
  state: TaskState;
  attempt: number;
  workerNodeCode: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  log: string | null;
}

// ─── TaskDiagnosis ───────────────────────────────────────

export interface DiagnosisSuggestion {
  action: string;
  label: string;
}

export interface TaskDiagnosis {
  id: number;
  taskInstanceId: number;
  taskId: number;
  workerNodeCode: string | null;
  title: string;
  rootCause: string;
  /** JSON string → parse to Record<string, unknown> */
  contextJson: string | null;
  /** JSON string → parse to DiagnosisSuggestion[] */
  suggestionsJson: string | null;
  status: "OPEN" | "RESOLVED";
}

// ─── DashboardSummary ────────────────────────────────────

export interface DashboardSummary {
  total: number;
  success: number;
  failed: number;
  running: number;
  failedInstances: TaskInstance[];
  diagnosing: TaskDiagnosis[];
}

// ─── FixResult ───────────────────────────────────────────

export interface FixResult {
  success: boolean;
  message: string;
  newInstanceId: number | null;
}

// ─── TaskDef ──────────────────────────────────────────────

export interface TaskDef {
  id: number
  name: string
  type: string // "SQL" etc.
  content: string // SQL text
  status: "ONLINE" | "DRAFT"
  currentVersionNo: number
  hasDraftChange: number
  priority: number
  description: string | null
  ownerId: number | null
  datasourceId: number | null
  targetDatasourceId: number | null
  paramsJson: string | null
  timeoutSec: number | null
  retryMax: number | null
  catalogNodeId: number | null // 所属类目文件夹（null=未分类）
  createdAt: string
  updatedAt: string | null
}

// ─── Catalog 类目树 + 标签 ───────────────────────────────

/** 类目树节点（GET /api/catalog/tree → data.roots[]）。 */
export interface CatalogTreeNode {
  id: number
  parentId: number | null
  name: string
  sortOrder: number | null
  taskCount: number
  workflowCount: number
  children: CatalogTreeNode[]
}

/** 整棵类目树（含未分类计数）。 */
export interface CatalogTree {
  roots: CatalogTreeNode[]
  uncategorizedTaskCount: number
  uncategorizedWorkflowCount: number
}

/** 标签（GET /api/tags）。 */
export interface Tag {
  id: number
  projectId: number
  name: string
  color: string | null
}

// ─── Workflow / DAG（画布编排）───────────────────────────

/** 工作流定义（GET /api/workflows/{id}）。 */
export interface WorkflowDef {
  id: number
  name: string
  description: string | null
  scheduleType: string // MANUAL | CRON | DEPENDENCY
  cron: string | null
  status: "ONLINE" | "DRAFT"
  currentVersionNo: number
  hasDraftChange: number
  priority: number | null
  preemptible: number | null
  timeoutSec: number | null
  catalogNodeId: number | null // 所属类目文件夹（null=未分类）
  version: number
  createdAt: string
  updatedAt: string | null
}

export type DagNodeType = "TASK" | "VIRTUAL"

/** DAG 节点（以 nodeKey 为稳定标识，与后端 DagNodeDto 对应）。 */
export interface DagNode {
  nodeKey: string
  nodeType: DagNodeType
  taskId: number | null // VIRTUAL 节点为 null
  name: string | null
  posX: number | null
  posY: number | null
}

/** DAG 边（端点用 nodeKey 引用）。 */
export interface DagEdge {
  fromNodeKey: string
  toNodeKey: string
}

/** 读图视图（GET /api/workflows/{id}/dag）。version 用于保存时乐观锁回传。 */
export interface DagView {
  workflowId: number
  version: number
  hasDraftChange: number
  status: string
  nodes: DagNode[]
  edges: DagEdge[]
}

/** 整图保存载荷（PUT /api/workflows/{id}/dag）。 */
export interface DagPayload {
  version: number | null
  nodes: DagNode[]
  edges: DagEdge[]
}

/** 分页结果（GET /api/workflows）。 */
export interface WorkflowPage {
  content: WorkflowDef[]
  totalElements: number
  totalPages: number
  page: number
  size: number
}

// ─── MetricCard ──────────────────────────────────────────

/** GET /api/metrics → 指标卡片（每 code 最新版本 + 口径求值） */
export interface MetricCard {
  id: number
  code: string
  name: string
  unit: string | null
  versionNo: number
  status: string | null
  /** 口径求值结果；求值失败为 null（前端呈现空态） */
  value: unknown
}

// ─── Helpers ─────────────────────────────────────────────

/** Safely parse a JSON string, returning null on failure. */
export function safeJsonParse<T>(json: string | null | undefined): T | null {
  if (!json) return null;
  try {
    return JSON.parse(json) as T;
  } catch {
    return null;
  }
}

/**
 * 按 UI locale 格式化日期时间（D9）。locale 由调用方经 next-intl `useLocale()` 传入；
 * 缺省时退回运行时默认 locale。zh-CN → `2026/06/18 14:30:00`，en-US → `06/18/2026, 14:30:00`。
 */
export function formatDateTime(
  iso: string | null | undefined,
  locale?: string,
): string {
  if (!iso) return "—";
  try {
    const d = new Date(iso);
    if (isNaN(d.getTime())) return iso;
    return new Intl.DateTimeFormat(locale, {
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
      hour12: false,
    }).format(d);
  } catch {
    return iso;
  }
}

/** Truncate a string to maxLen, appending "…" if truncated. */
export function truncate(s: string | null | undefined, maxLen = 60): string {
  if (!s) return "—";
  return s.length > maxLen ? s.slice(0, maxLen) + "…" : s;
}
