"use client"

import { HugeiconsIcon } from "@hugeicons/react"
import { Cancel01Icon } from "@hugeicons/core-free-icons"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
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

/** kind → 中文标题（无 title 时兜底）。 */
const KIND_LABEL: Record<string, string> = {
  table: "查询结果",
  fleet: "节点机群",
  lineage: "血缘",
  task: "任务",
  metric: "指标",
  diagnosis: "诊断",
}

/** 用 shadcn `Table` 富渲染 Agent 返回的结构化表格（MVP 4.6），与 markdown 表格互补。 */
export function ResultTable({ data, onClose }: { data: ResultData; onClose: () => void }) {
  const { columns, rows, kind, title, sql } = data
  return (
    <div className="flex flex-col gap-2 rounded-[var(--radius-md)] border bg-card px-3 py-2.5 shadow-sm">
      <div className="flex items-center gap-2">
        <span className="text-sm font-medium">{title ?? KIND_LABEL[kind ?? ""] ?? "结果"}</span>
        {kind && <Badge className="bg-muted text-muted-foreground">{kind}</Badge>}
        <span className="ml-auto text-xs text-muted-foreground">{rows.length} 行</span>
        <Button
          size="icon"
          variant="ghost"
          className="size-6"
          onClick={onClose}
          aria-label="关闭结果"
        >
          <HugeiconsIcon icon={Cancel01Icon} className="size-3.5" />
        </Button>
      </div>
      {sql && (
        <pre className="overflow-x-auto rounded-[var(--radius-sm)] bg-muted/50 px-2 py-1 text-xs">
          <code>{sql}</code>
        </pre>
      )}
      <div className="max-h-72 overflow-auto rounded-[var(--radius-sm)] border">
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
                  无数据
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
      </div>
    </div>
  )
}

/** 单元格值格式化：null → 占位符；对象 → JSON；其余 → 字符串。 */
function fmtCell(v: unknown): string {
  if (v == null) return "—"
  if (typeof v === "object") return JSON.stringify(v)
  return String(v)
}
