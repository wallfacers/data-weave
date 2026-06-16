/**
 * 代码编辑器工具栏的**纯决策逻辑** —— 与 Monaco/React 解耦，便于单测。
 * 组件 `components/code-editor.tsx` 消费这些函数渲染工具栏与处理剪贴板降级。
 */

/** 内建 formatter 的语言（Monaco 原生）；其余（sql/bash/python）无 provider，格式化置灰 */
export const FORMATTABLE_LANGS: ReadonlySet<string> = new Set([
  "json",
  "typescript",
  "javascript",
])

export function isFormattable(language: string): boolean {
  return FORMATTABLE_LANGS.has(language)
}

export type ClipboardCaps = { canWrite: boolean; canRead: boolean }

/**
 * 探测剪贴板读/写能力（读权限更易缺失，分开探测）。SSR 安全：传 undefined 视作不可用。
 */
export function clipboardCaps(nav?: { clipboard?: Clipboard } | undefined): ClipboardCaps {
  const clip = nav?.clipboard
  if (!clip) return { canWrite: false, canRead: false }
  return {
    canWrite: typeof clip.writeText === "function",
    canRead: typeof clip.readText === "function",
  }
}

export type ToolbarAction = "copy" | "paste" | "format" | "clear" | "find"

/**
 * readOnly 收敛：只读时只留 复制 / 查找（粘贴/格式化/清空是写操作，对只读视图无意义）。
 */
export function toolbarActions(readOnly: boolean): ToolbarAction[] {
  return readOnly
    ? ["copy", "find"]
    : ["copy", "paste", "format", "clear", "find"]
}
