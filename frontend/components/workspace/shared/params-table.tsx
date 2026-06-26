"use client"

import { useTranslations } from "next-intl"

/** 任务类型 → Shiki lang 映射。未知类型返回 "text"（无高亮）。 */
export function taskTypeToLang(taskType: string): string {
  const m: Record<string, string> = {
    SQL: "sql",
    PYTHON: "python",
    SHELL: "bash",
    JAVA: "java",
    JAVASCRIPT: "javascript",
    TYPESCRIPT: "typescript",
    JSON: "json",
    YAML: "yaml",
    XML: "html",
    BASH: "bash",
    SPARK: "scala",
  }
  return m[taskType.toUpperCase()] || "text"
}

/** 单行信息展示 */
export function InfoRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-baseline gap-2">
      <span className="text-xs text-muted-foreground shrink-0">{label}</span>
      <span className="text-xs text-foreground truncate">{value}</span>
    </div>
  )
}

/** 配置参数键值对表格 */
export function ParamsTable({ paramsJson }: { paramsJson: string }) {
  const t = useTranslations("ops")
  let params: Record<string, string> = {}
  try {
    params = JSON.parse(paramsJson)
  } catch {
    return <pre className="text-xs text-muted-foreground p-2 whitespace-pre-wrap">{paramsJson}</pre>
  }
  const entries = Object.entries(params)
  if (entries.length === 0) {
    return <span className="text-xs text-muted-foreground">{t("nodeDetail.noParams")}</span>
  }
  return (
    <table className="w-full text-xs">
      <tbody>
        {entries.map(([k, v]) => (
          <tr key={k} className="border-b border-border/50">
            <td className="py-1 pr-2 font-medium text-foreground/70 whitespace-nowrap">{k}</td>
            <td className="py-1 text-muted-foreground break-all">{String(v)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}
