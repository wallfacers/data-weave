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
  createdAt: string
  updatedAt: string | null
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

/** Format a datetime string to yyyy-MM-dd HH:mm:ss. */
export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return "—";
  try {
    const d = new Date(iso);
    if (isNaN(d.getTime())) return iso;
    const pad = (n: number) => String(n).padStart(2, "0");
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
  } catch {
    return iso;
  }
}

/** Truncate a string to maxLen, appending "…" if truncated. */
export function truncate(s: string | null | undefined, maxLen = 60): string {
  if (!s) return "—";
  return s.length > maxLen ? s.slice(0, maxLen) + "…" : s;
}
