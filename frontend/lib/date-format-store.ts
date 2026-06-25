/**
 * 日期格式偏好 store（zustand + localStorage 持久化）。
 *
 * 用户可在「项目设置」中选择日期显示风格；所有时间展示处（表格、面板、标签）
 * 统一读取此 store。`formatDateTime` 在 `lib/types.ts` 中读取此 store 的当前值。
 *
 * 初始值同步从 localStorage 读取，保证首屏即为用户偏好，无闪烁。
 *
 * 格式化使用 `date-fns` 的 `format`，确保输出与偏好完全一致，
 * 不受 Intl locale 分隔符差异影响（如 `en-CA` 会加逗号）。
 */
import { create } from "zustand"

export type DateFormatKey = "dash" | "slash"

interface DateFormatState {
  format: DateFormatKey
  setFormat: (f: DateFormatKey) => void
}

const STORAGE_KEY = "dw.date.format"
const DEFAULT: DateFormatKey = "dash"

export const DATE_FORMAT_OPTIONS: Array<{
  key: DateFormatKey
  /** 示例展示文本，用于设置页预览 */
  example: string
}> = [
  { key: "dash", example: "2026-06-25 14:30:00" },
  { key: "slash", example: "2026/06/25 14:30:00" },
]

/** 从 localStorage 同步读取初始值；SSR/无 localStorage 时回退默认 */
function readInitialFormat(): DateFormatKey {
  if (typeof window === "undefined") return DEFAULT
  try {
    const saved = localStorage.getItem(STORAGE_KEY) as DateFormatKey | null
    if (saved && DATE_FORMAT_OPTIONS.some((o) => o.key === saved)) return saved
  } catch { /* localStorage 不可用（隐私模式等） */ }
  return DEFAULT
}

export const dateFormatStore = create<DateFormatState>((set) => ({
  format: readInitialFormat(),
  setFormat: (format) => {
    set({ format })
    if (typeof window !== "undefined") {
      localStorage.setItem(STORAGE_KEY, format)
    }
  },
}))

/**
 * 返回当前偏好对应的 `date-fns` format pattern。
 * - dash  → `yyyy-MM-dd HH:mm:ss`
 * - slash → `yyyy/MM/dd HH:mm:ss`
 */
export function resolveDateFormatPattern(format: DateFormatKey): string {
  switch (format) {
    case "slash":
      return "yyyy/MM/dd HH:mm:ss"
    case "dash":
    default:
      return "yyyy-MM-dd HH:mm:ss"
  }
}
