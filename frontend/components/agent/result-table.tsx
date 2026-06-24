"use client"

import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import { Cancel01Icon } from "@hugeicons/core-free-icons"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { DwScroll } from "@/components/ui/dw-scroll"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"

/**
 * 结构化结果（dataweave.result / dataweave.fleet 等 CUSTOM 事件负载）富渲染数据。
 * 凡含 columns + rows 即视为表格（kind ∈ table/fleet/…）；rows 为后端 `List<Map>`（按列名取值）。
 */
export interface ResultData {
  id: number
  kind?: string
  title?: string
  sql?: string
  columns: string[]
  rows: Record<string, unknown>[]
}

/** kind → i18n key（无 title 时兜底标题）。 */
const KIND_LABEL_KEY: Record<string, string> = {
  table: "kindTable",
  fleet: "kindFleet",
  lineage: "kindLineage",
  task: "kindTask",
  metric: "kindMetric",
  diagnosis: "kindDiagnosis",
}

/** 用 shadcn `Table` 富渲染 Agent 返回的结构化表格（MVP 4.6），与 markdown 表格互补。 */
export function ResultTable({ data, onClose }: { data: ResultData; onClose: () => void }) {
  const t = useTranslations("resultTable")
  const { columns, rows, kind, title, sql } = data
  const kindKey = KIND_LABEL_KEY[kind ?? ""]
  return (
    <div className="flex flex-col gap-2 rounded-[var(--radius-md)] border bg-card px-3 py-2.5 shadow-sm">
      <div className="flex items-center gap-2">
        <span className="text-sm font-medium">{title ?? (kindKey ? t(kindKey) : t("fallbackTitle"))}</span>
        {kind && <Badge className="bg-muted text-muted-foreground">{kind}</Badge>}
        <span className="ml-auto text-xs text-muted-foreground">{t("rowCount", { count: rows.length })}</span>
        <Button
          size="icon"
          variant="ghost"
          className="size-6"
          onClick={onClose}
          aria-label={t("closeLabel")}
        >
          <HugeiconsIcon icon={Cancel01Icon} className="size-3.5" />
        </Button>
      </div>
      {sql && (
        <DwScroll direction="horizontal" className="rounded-[var(--radius-sm)] bg-muted/50 px-2 py-1">
          <pre className="text-xs">
            <code>{sql}</code>
          </pre>
        </DwScroll>
      )}
      <DwScroll className="max-h-72 rounded-[var(--radius-sm)] border">
        <Table>
          <TableHeader>
            <TableRow>
              {columns.map((c) => (
                <TableHead key={c}>{c}</TableHead>
              ))}
            </TableRow>
          </TableHeader>
          <TableBody>
            {rows.length === 0 ? (
              <TableRow>
                <TableCell
                  colSpan={Math.max(columns.length, 1)}
                  className="text-center text-muted-foreground"
                >
                  {t("empty")}
                </TableCell>
              </TableRow>
            ) : (
              rows.map((row, i) => (
                <TableRow key={i}>
                  {columns.map((c) => (
                    <TableCell key={c}>{fmtCell(row[c])}</TableCell>
                  ))}
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
      </DwScroll>
    </div>
  )
}

/** 单元格值格式化：null → 占位符；对象 → JSON；其余 → 字符串。 */
function fmtCell(v: unknown): string {
  if (v == null) return "—"
  if (typeof v === "object") return JSON.stringify(v)
  return String(v)
}
