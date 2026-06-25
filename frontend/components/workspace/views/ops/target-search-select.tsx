"use client"

/**
 * 补数据目标的可搜索选择器：按名称搜索任务/工作流,替代手输数字 ID。
 * - 防抖请求 GET /api/tasks?keyword= 或 /api/workflows?keyword=
 * - 候选项展示 名称 · catalog 路径 · 状态;选中后内部持有 id,数字 ID 对用户隐形
 * - 面板用相对容器 + 绝对定位(置于 Dialog 内,随同一 transform 上下文,无需 portal)
 */

import { useEffect, useRef, useState } from "react"
import { useTranslations } from "next-intl"
import { cn } from "@/lib/utils"
import { authFetch, API_BASE, type ApiResponse } from "@/lib/types"
import { DwScroll } from "@/components/ui/dw-scroll"

export interface TargetOption {
  id: number
  name: string
  type: "task" | "workflow"
  path?: string
  status?: string
}

interface TargetSearchSelectProps {
  value: TargetOption | null
  onChange: (v: TargetOption | null) => void
  /** catalogNodeId → 路径字符串(由外层一次性加载的类目树构建) */
  pathMap?: Map<number, string>
}

interface RawDef {
  id: number
  name: string
  status?: string
  catalogNodeId: number | null
}

export function TargetSearchSelect({ value, onChange, pathMap }: TargetSearchSelectProps) {
  const t = useTranslations("ops")
  const [open, setOpen] = useState(false)
  const [kw, setKw] = useState("")
  const [results, setResults] = useState<TargetOption[]>([])
  const [loading, setLoading] = useState(false)
  const wrapRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLInputElement>(null)
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  // 打开期间点外部关闭
  useEffect(() => {
    if (!open) return
    const onDown = (e: MouseEvent) => {
      if (!wrapRef.current?.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener("mousedown", onDown, true)
    return () => document.removeEventListener("mousedown", onDown, true)
  }, [open])

  // 打开时聚焦搜索框
  useEffect(() => {
    if (open) inputRef.current?.focus()
  }, [open])

  // 防抖搜索(打开期间;关键词变化触发);并行搜索 task + workflow
  useEffect(() => {
    if (!open) return
    if (debounceRef.current) clearTimeout(debounceRef.current)
    debounceRef.current = setTimeout(async () => {
      setLoading(true)
      try {
        const q = encodeURIComponent(kw.trim())
        const [taskRes, wfRes] = await Promise.all([
          authFetch(`${API_BASE}/api/tasks?keyword=${q}&size=20`),
          authFetch(`${API_BASE}/api/workflows?keyword=${q}&size=20`),
        ])
        const parse = async (r: Response, type: "task" | "workflow"): Promise<TargetOption[]> => {
          const json = (await r.json().catch(() => null)) as ApiResponse<unknown> | null
          const data = json && json.code === 0 ? (json.data as Record<string, unknown> | null) : null
          const content = data && Array.isArray(data.content) ? (data.content as RawDef[]) : []
          return content.map((item) => ({
            id: item.id,
            name: item.name,
            type,
            path: item.catalogNodeId != null ? pathMap?.get(item.catalogNodeId) : undefined,
            status: item.status,
          }))
        }
        const [tasks, workflows] = await Promise.all([parse(taskRes, "task"), parse(wfRes, "workflow")])
        setResults([...tasks, ...workflows])
      } catch {
        setResults([])
      } finally {
        setLoading(false)
      }
    }, 250)
    return () => {
      if (debounceRef.current) clearTimeout(debounceRef.current)
    }
  }, [kw, open, pathMap])

  return (
    <div ref={wrapRef} className="relative">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        className={cn(
          "flex h-8 w-full min-w-0 items-center justify-between gap-1 rounded-md border border-input bg-background px-2 text-sm transition-colors hover:bg-muted",
          value ? "text-foreground" : "text-muted-foreground",
        )}
        aria-expanded={open}
      >
        <span className="min-w-0 flex-1 truncate text-left">
          {value ? (
            <span className="flex items-center gap-1.5">
              <span className="shrink-0 rounded bg-muted px-1 font-mono text-[10px]">
                {value.type === "task" ? t("backfillTargetTypeTask") : t("backfillTargetTypeWorkflow")}
              </span>
              {value.name}
            </span>
          ) : (
            t("backfillTargetPickHint")
          )}
        </span>
        <svg
          xmlns="http://www.w3.org/2000/svg"
          width="16"
          height="16"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="2"
          strokeLinecap="round"
          strokeLinejoin="round"
          className="size-3.5 shrink-0 text-muted-foreground"
        >
          <path d="m6 9 6 6 6-6" />
        </svg>
      </button>

      {open && (
        <div className="absolute left-0 right-0 top-[calc(100%+4px)] z-50 flex max-h-72 flex-col rounded-lg border bg-popover shadow-md">
          <div className="border-b p-1.5">
            <input
              ref={inputRef}
              value={kw}
              onChange={(e) => setKw(e.target.value)}
              placeholder={t("backfillTargetSearchPh")}
              className="h-7 w-full rounded-md bg-transparent px-2 text-sm outline-none placeholder:text-muted-foreground"
            />
          </div>
          <DwScroll className="flex-1 min-h-0" direction="vertical">
            <div className="flex flex-col gap-0.5 p-1">
            {loading && kw.trim() ? (
              <div className="px-2 py-3 text-center text-xs text-muted-foreground">
                {t("backfillTargetSearching")}
              </div>
            ) : results.length === 0 ? (
              <div className="px-2 py-3 text-center text-xs text-muted-foreground">
                {kw.trim() ? t("backfillTargetNoResult") : t("backfillTargetPickHint")}
              </div>
            ) : (
              results.map((opt) => (
                <button
                  key={`${opt.type}-${opt.id}`}
                  type="button"
                  onClick={() => {
                    onChange(opt)
                    setOpen(false)
                  }}
                  className={cn(
                    "flex w-full flex-col gap-0.5 rounded-md px-2 py-1.5 text-left hover:bg-muted",
                    opt.type === value?.type && opt.id === value?.id && "bg-muted",
                  )}
                >
                  <span className="truncate text-sm font-medium text-foreground">{opt.name}</span>
                  <span className="flex items-center gap-1.5 truncate text-xs text-muted-foreground">
                    <span className="shrink-0 rounded bg-muted px-1 font-mono text-[10px]">
                      {opt.type === "task" ? t("backfillTargetTypeTask") : t("backfillTargetTypeWorkflow")}
                    </span>
                    {opt.path && <span className="truncate">{opt.path}</span>}
                    {opt.status && (
                      <span className="shrink-0 rounded bg-muted px-1 font-mono text-[10px] uppercase">
                        {opt.status}
                      </span>
                    )}
                  </span>
                </button>
              ))
            )}
            </div>
          </DwScroll>
        </div>
      )}
    </div>
  )
}

/** 把类目树扁平成 catalogNodeId → "父 / 子" 路径串。 */
export function buildCatalogPathMap(
  roots: Array<{ id: number; name: string; children?: unknown[] }>,
): Map<number, string> {
  const map = new Map<number, string>()
  const walk = (nodes: Array<{ id: number; name: string; children?: unknown[] }>, prefix: string) => {
    for (const n of nodes) {
      const path = prefix ? `${prefix} / ${n.name}` : n.name
      map.set(n.id, path)
      if (Array.isArray(n.children) && n.children.length > 0) {
        walk(n.children as Array<{ id: number; name: string; children?: unknown[] }>, path)
      }
    }
  }
  walk(roots, "")
  return map
}
