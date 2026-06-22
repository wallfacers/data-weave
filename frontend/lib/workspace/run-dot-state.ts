/**
 * 运行 Tab 状态圆点的纯派生逻辑（run-tab-status-dot）：从 SSE 日志流的终态 outcome /
 * 是否已 end / 是否仍 connected 合成圆点状态，并提供状态→颜色映射。抽出为纯函数便于单测。
 *
 * 语义：圆点反映实例执行状态（非 SSE 连接状态）——运行中 / 远程完成 / 运行错误 / 已终止 / 连接中。
 */
export type RunDotState = "running" | "success" | "failed" | "stopped" | "connecting"

/** 终态 → 圆点颜色（语义 token，与 log-panel.tsx 的 StatusDot 一致；不硬编码 emerald/amber）。 */
export const runDotColor: Record<RunDotState, string> = {
  running: "bg-success animate-pulse",
  success: "bg-success",
  failed: "bg-destructive",
  stopped: "bg-muted-foreground",
  connecting: "bg-warning animate-pulse",
}

/**
 * 从 SSE `end` 事件 data 解析终态 outcome（后端负载 `{"state":"SUCCESS"}`）。
 * 容错空 / 旧 / 非 JSON 负载：解析失败一律返回 null（绝不抛出、不误判终态）。
 */
export function parseEndState(data: string | null | undefined): string | null {
  if (!data) return null
  try {
    const parsed = JSON.parse(data)
    return typeof parsed?.state === "string" ? parsed.state : null
  } catch {
    return null
  }
}

/**
 * 合成圆点状态：终态 outcome 覆盖（success/failed/stopped）；无 outcome 时——已 end 则中性灰
 * （兼容旧/空负载），仍 connected 则运行中，否则连接中。终态后不再回退为运行中（outcome/ended 优先）。
 */
export function deriveRunDotState(
  outcome: string | null,
  ended: boolean,
  connected: boolean,
): RunDotState {
  if (outcome === "SUCCESS") return "success"
  if (outcome === "FAILED") return "failed"
  if (outcome === "STOPPED") return "stopped"
  if (ended) return "stopped"
  if (connected) return "running"
  return "connecting"
}
