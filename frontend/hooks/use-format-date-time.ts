"use client"

/**
 * 响应式读取日期格式偏好 store，返回格式化函数 `(iso) => string`。
 * store 变化时自动触发组件重渲染。
 */
import { useCallback, useSyncExternalStore } from "react"
import { format as dateFnsFormat } from "date-fns"

import { dateFormatStore, resolveDateFormatPattern, type DateFormatKey } from "@/lib/date-format-store"

function subscribe(cb: () => void) {
  return dateFormatStore.subscribe(cb)
}

export function useFormatDateTime(): (iso: string | null | undefined) => string {
  const format = useSyncExternalStore(subscribe, () => dateFormatStore.getState().format)

  return useCallback(
    (iso: string | null | undefined) => formatDateTimeWithFormat(iso, format),
    [format],
  )
}

/**
 * 纯函数：用指定格式格式化 ISO 时间字符串。
 * 非 React 上下文（如模板字符串内联）也可直接调用。
 */
export function formatDateTimeWithFormat(
  iso: string | null | undefined,
  format: DateFormatKey,
): string {
  if (!iso) return "—"
  try {
    const d = new Date(iso)
    if (isNaN(d.getTime())) return iso
    return dateFnsFormat(d, resolveDateFormatPattern(format))
  } catch {
    return iso
  }
}
