/**
 * 项目隔离 header 工具（036）。
 *
 * 读取当前选中的项目 ID，供所有 API 客户端注入 X-Project-Id header。
 * 直接读 localStorage（key 与 project-context.ts 的 STORAGE_KEY 一致），
 * 避免从 project-context 导入导致循环依赖（project-context → project-api → types）。
 */

const PROJECT_ID_KEY = "dw.project.current"

/** 从 localStorage 同步读取当前项目 id，SSR / 未就绪时回退 "1"。 */
export function readProjectId(): string {
  if (typeof window === "undefined") return "1"
  try {
    const saved = localStorage.getItem(PROJECT_ID_KEY)
    if (saved != null && /^\d+$/.test(saved)) return saved
  } catch {
    /* localStorage 不可用（隐私模式等） */
  }
  return "1"
}

/** 返回 { "X-Project-Id": "<当前项目ID>" }，方便 spread。 */
export function projectIdHeader(): Record<string, string> {
  return { "X-Project-Id": readProjectId() }
}
