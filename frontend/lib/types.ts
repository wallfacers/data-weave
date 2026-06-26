/**
 * 后端 REST API 类型定义（与 Java domain 对象字段一一对应，camelCase）。
 *
 * 端点：
 *  - GET  /api/ops/summary     → DashboardSummary
 *  - GET  /api/ops/failed      → TaskInstance[]
 *  - GET  /api/fleet           → WorkerNode[]
 *  - GET  /api/fleet/{nodeCode}→ WorkerNode
 */

import { format as dateFnsFormat } from "date-fns"
import { dateFormatStore, resolveDateFormatPattern } from "./date-format-store"

export const API_BASE = "";

/**
 * SSE/EventSource 专用基址：**必须直连后端**，不能走相对路径经 Next.js rewrite 代理。
 *
 * Next dev（Turbopack）的 rewrite 代理会**缓冲流式响应**——把逐行 SSE 攒到上游关闭流
 * 才一次性下发，导致实时日志「一把输出」而非滚屏。普通 REST 走相对路径（被代理）没问题，
 * 但 SSE 必须绕过代理直连 `:8000`（CORS 已放行 `localhost:4000` 源）。
 * 生产部署需将 `NEXT_PUBLIC_BACKEND_URL` 指向浏览器可达的后端地址，并在后端 CORS 放行该源。
 */
export const SSE_BASE = process.env.NEXT_PUBLIC_BACKEND_URL ?? "http://localhost:8000";

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

/**
 * 最近活跃实例视图（run-state-resume）：后端 `/api/ops/tasks/{id}/latest-instance`
 * 与 `/api/ops/workflows/{id}/latest-instance` 返回。无实例时整个 data 为 null。
 * id 为实例 UUID（字符串）；state 为后端实例状态;runMode 任务区分 NORMAL/TEST,工作流恒 NORMAL。
 */
export interface LatestInstanceView {
  id: string;
  state: string;
  runMode: string;
}

// ─── DashboardSummary ────────────────────────────────────

export interface DashboardSummary {
  total: number;
  success: number;
  failed: number;
  running: number;
  failedInstances: TaskInstance[];
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
  frozen: number | null         // 0=正常 1=冻结
  catalogNodeId: number | null // 所属类目文件夹（null=未分类）
  createdAt: string
  updatedAt: string | null
}

/** 任务发布版本快照（task_def_version，不可变历史）。 */
export interface TaskDefVersion {
  id: number
  taskId: number
  versionNo: number
  name: string
  type: string
  content: string
  datasourceId: number | null
  targetDatasourceId: number | null
  paramsJson: string | null
  timeoutSec: number | null
  retryMax: number | null
  priority: number
  description: string | null
  remark: string | null
  publishedBy: number | null
  publishedAt: string
}

/** 任务详情（GET /api/tasks/{id} 返回，含版本历史）。 */
export interface TaskDetail {
  task: TaskDef
  versions: TaskDefVersion[]
  /** 引用此任务的 ONLINE 工作流名单（ops-center-publish-boundary）：非空则禁止下线，前端禁用下线按钮。 */
  referencedByOnlineWorkflows?: string[]
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
  lastFireTime: string | null
  priority: number | null
  preemptible: number | null
  timeoutSec: number | null
  catalogNodeId: number | null // 所属类目文件夹（null=未分类）
  version: number
  createdAt: string
  updatedAt: string | null
}

/** 工作流发布版本快照（workflow_def_version，不可变历史）。 */
export interface WorkflowDefVersion {
  id: number
  workflowId: number
  versionNo: number
  name: string
  description: string | null
  scheduleType: string
  cron: string | null
  dagSnapshotJson: string | null
  remark: string | null
  publishedBy: number | null
  publishedAt: string
}

/** 工作流详情（GET /api/workflows/{id} 返回，含版本历史）。 */
export interface WorkflowDetail {
  workflow: WorkflowDef
  versions: WorkflowDefVersion[]
}

/** 漂移节点：快照钉死版本（pinned）落后于任务最新发布版（latest）。与后端 WorkflowService.DriftNode 对应。 */
export interface DriftNode {
  nodeKey: string
  pinned: number
  latest: number
}

/**
 * 工作流漂移（读侧计算，不落库）。与后端 WorkflowService.DriftResult 对应。
 * drifted = 任意节点 task 版本落后（driftedNodes 非空）或 DAG 草稿漂移（dagDraft）。
 */
export interface DriftResult {
  drifted: boolean
  dagDraft: boolean
  driftedNodes: DriftNode[]
}

export type DagNodeType = "TASK" | "VIRTUAL"

/** DAG 节点（以 nodeKey 为稳定标识，与后端 DagNodeDto 对应）。 */
export interface DagNode {
  nodeKey: string
  nodeType: DagNodeType
  taskId: number | null // VIRTUAL 节点为 null
  /** 发布时冻结的任务版本号（published-dag 返回，供节点详情面板查询；draft-dag 为 null） */
  taskVersionNo?: number | null
  name: string | null
  posX: number | null
  posY: number | null
  /** 引用任务发布态（ONLINE/DRAFT）：供画布标「未发布」节点；VIRTUAL/缺失为 null（读图返回，保存不回传） */
  taskStatus?: string | null
}

/**
 * DAG 边（端点用 nodeKey 引用）。strength=依赖强度：
 *  - STRONG（默认）上游须 SUCCESS 才放行下游；
 *  - WEAK 上游「自然跑完」（SUCCESS/FAILED）即放行下游；手动停止（STOPPED）不放行（ops-center-publish-boundary）。
 */
export interface DagEdge {
  fromNodeKey: string
  toNodeKey: string
  strength?: "STRONG" | "WEAK"
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

/**
 * 手动运行范围（manual-run-scope）：
 *  - FULL=全图；TO_NODE=运行到目标节点（含其全部前驱）；DOWNSTREAM=运行目标节点全部下游；
 *  - ONLY_NODE=仅运行单个任务节点（走 /api/tasks/{id}/run，脱离工作流）。
 */
export type RunScope = "FULL" | "TO_NODE" | "DOWNSTREAM" | "ONLY_NODE"

/** 手动触发工作流运行请求体（POST /api/workflows/{id}/run）。scope 缺省 FULL。 */
export interface RunWorkflowRequest {
  bizDate?: string | null
  scope?: RunScope
  targetNodeKey?: string | null
}

/** 跨周期依赖（GET/POST/DELETE /api/workflows/{id}/dependencies）。前端用 nodeKey/dependNodeKey（画布标识），后端内部转 workflow_node.id。 */
export interface WorkflowDependency {
  id?: number | null
  nodeKey: string
  dependWorkflowId: number | null
  dependNodeKey: string | null
  dateOffset: string
  earliestBizDate: string | null
  enabled: number
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
 * 按用户偏好格式化日期时间。读取 `dateFormatStore` 的偏好（dash/slash）用 `date-fns` 格式化。
 *
 * React 组件应改用 `useFormatDateTime()` hook（`hooks/use-format-date-time.ts`），
 * 以便在偏好变更时自动重渲染；此函数供非 React 上下文或一次性调用使用。
 */
export function formatDateTime(
  iso: string | null | undefined,
  _locale?: string,
): string {
  if (!iso) return "—";
  try {
    const d = new Date(iso);
    if (isNaN(d.getTime())) return iso;
    const pattern = resolveDateFormatPattern(dateFormatStore.getState().format);
    return dateFnsFormat(d, pattern);
  } catch {
    return iso;
  }
}

/** Truncate a string to maxLen, appending "…" if truncated. */
export function truncate(s: string | null | undefined, maxLen = 60): string {
  if (!s) return "—";
  return s.length > maxLen ? s.slice(0, maxLen) + "…" : s;
}

// ─── Datasource ──────────────────────────────────────────

/** 数据源类型元数据（GET /api/datasource-types）。 */
export interface DatasourceType {
  id: number
  code: string
  name: string
  category: string  // RDB | MPP | NOSQL | STORAGE
  driver: string | null
  defaultPort: number | null
}

/** 数据源实体（GET /api/datasources）。密码字段始终脱敏为 "******"。 */
export interface DatasourceVO {
  id: number
  tenantId: number
  projectId: number
  name: string
  typeCode: string
  host: string | null
  port: number | null
  databaseName: string | null
  jdbcUrl: string | null
  username: string | null
  passwordEnc: string  // always "******"
  propsJson: string | null
  description: string | null
  status: string
  connectionStatus: string  // "UNKNOWN" | "CONNECTED" | "DISCONNECTED"
  driverJarId: number | null
  driverSource: string  // "builtin" | "uploaded"
  createdAt: string | null
  updatedAt: string | null
}

/** 连通性测试结果（POST /api/datasources/{id}/test）。 */
export interface ConnectionTestResult {
  success: boolean
  message: string
  latencyMs: number
  serverVersion: string | null
}

/** 删除结果（DELETE /api/datasources/{id}）。 */
export interface DatasourceDeleteResult {
  deleted: boolean
  referencedTaskCount: number
  warning: string | null
}

/** 创建数据源请求体。 */
export interface DatasourceCreateRequest {
  name: string
  typeCode: string
  projectId?: number
  host?: string | null
  port?: number | null
  databaseName?: string | null
  jdbcUrl?: string | null
  username?: string | null
  password?: string | null
  propsJson?: string | null
  description?: string | null
  driverJarId?: number | null
}

/** 更新数据源请求体（所有字段可选）。 */
export interface DatasourceUpdateRequest {
  name?: string | null
  typeCode?: string | null
  host?: string | null
  port?: number | null
  databaseName?: string | null
  jdbcUrl?: string | null
  username?: string | null
  password?: string | null
  propsJson?: string | null
  description?: string | null
  status?: string | null
  driverJarId?: number | null
}

/** 驱动 jar 资产（GET/POST /api/driver-jars）。sha256 仅展示短摘要。 */
export interface DriverJarVO {
  id: number
  typeCode: string
  driverClass: string | null
  originalName: string | null
  sha256Short: string | null
  storageType: string | null
  sizeBytes: number | null
  status: string
}
