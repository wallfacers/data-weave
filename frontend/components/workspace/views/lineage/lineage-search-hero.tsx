"use client"

/**
 * 054 US1：血缘探索器「搜索优先」入口组件。
 *
 * - {@link LineageSearchHero}：未锚定资产时画布居中的搜索 hero——大输入框 + 默认聚焦 +
 *   「搜一个资产开始」引导（FR-001/005）。提交/回车触发（research D4），与 052 一致。
 * - {@link LineageSearchCandidates}：搜索候选列表（复用于 hero 与工具栏下拉），每项显示
 *   数据源色点 + 名称 + 所属数据源名（同名跨库可辨，FR-003/SC-005）+ 分层 + 类型。
 *
 * 设计约束（DESIGN.md）：复用 Input 规范原语、语义 token、gap-* / size-*，不手写同类原语、不手写 dark:。
 */
import { useRef, useEffect } from "react"
import { useTranslations } from "next-intl"
import { Input } from "@/components/ui/input"
import { DwScroll } from "@/components/ui/dw-scroll"
import type { SearchCandidate } from "@/lib/lineage-api"
import { datasourceColor } from "@/lib/workspace/lineage-datasource-style"

export interface LineageSearchHeroProps {
  query: string
  searching: boolean
  candidates: SearchCandidate[]
  onQueryChange: (q: string) => void
  onSubmit: () => void
  onSelect: (c: SearchCandidate) => void
  /** 点击搜索区域外部时关闭候选列表 */
  onDismiss: () => void
}

/** 未锚定时的搜索 hero：画布居中、默认聚焦、提交/回车触发；候选直接列于输入框下。 */
export function LineageSearchHero({ query, searching, candidates, onQueryChange, onSubmit, onSelect, onDismiss }: LineageSearchHeroProps) {
  const t = useTranslations("lineageView")
  const containerRef = useRef<HTMLDivElement>(null)

  // 点击搜索区域外部时关闭候选列表
  useEffect(() => {
    if (candidates.length === 0) return
    const handler = (e: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        onDismiss()
      }
    }
    document.addEventListener("mousedown", handler)
    return () => document.removeEventListener("mousedown", handler)
  }, [candidates.length, onDismiss])

  return (
    <div ref={containerRef} className="absolute inset-0 z-20 flex flex-col items-center justify-center gap-4 bg-background px-6">
      <div className="flex w-full max-w-xl flex-col items-center gap-3">
        <p className="text-sm text-muted-foreground">{t("searchHeroHint")}</p>
        <form
          className="w-full"
          onSubmit={(e) => {
            e.preventDefault()
            onSubmit()
          }}
        >
          <Input
            type="text"
            value={query}
            autoFocus
            onChange={(e) => onQueryChange(e.target.value)}
            placeholder={t("searchPlaceholder")}
            className="h-11 text-base"
          />
        </form>
        {searching && <p className="text-xs text-muted-foreground">{t("searching")}</p>}
        {candidates.length > 0 && (
          <DwScroll className="max-h-64 w-full rounded-md border bg-popover shadow-lg">
            <LineageSearchCandidates candidates={candidates} onSelect={onSelect} />
          </DwScroll>
        )}
      </div>
    </div>
  )
}

export interface LineageSearchCandidatesProps {
  candidates: SearchCandidate[]
  onSelect: (c: SearchCandidate) => void
}

export interface SearchCandidatesOverlayProps {
  candidates: SearchCandidate[]
  onSelect: (c: SearchCandidate) => void
  onDismiss: () => void
}

/**
 * 工具栏搜索候选下拉叠加层（已锚定时使用），带点击外部关闭和项目规范滚动条。
 */
export function SearchCandidatesOverlay({ candidates, onSelect, onDismiss }: SearchCandidatesOverlayProps) {
  const overlayRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (overlayRef.current && !overlayRef.current.contains(e.target as Node)) {
        onDismiss()
      }
    }
    document.addEventListener("mousedown", handler)
    return () => document.removeEventListener("mousedown", handler)
  }, [onDismiss])

  return (
    <div ref={overlayRef} className="relative z-30 mx-3 -mb-1">
      <div className="absolute top-0 left-0 right-0 rounded-md border bg-popover shadow-lg">
        <DwScroll className="max-h-48 w-full">
          <LineageSearchCandidates candidates={candidates} onSelect={onSelect} />
        </DwScroll>
      </div>
    </div>
  )
}

/** 搜索候选列表：数据源色点 + 名称 + 数据源名（同名跨库区分）+ 分层 + 类型。 */
export function LineageSearchCandidates({ candidates, onSelect }: LineageSearchCandidatesProps) {
  return (
    <div className="flex flex-col p-1">
      {candidates.map((c) => (
        <button
          key={c.id}
          type="button"
          className="flex items-center gap-2 rounded px-2 py-1.5 text-left text-xs transition-colors hover:bg-muted"
          onClick={() => onSelect(c)}
        >
          {c.datasourceName && (
            <span
              className="size-2 shrink-0 rounded-full"
              style={{ backgroundColor: datasourceColor(c.datasourceName) }}
              aria-hidden
            />
          )}
          <span className="truncate font-medium">{c.name}</span>
          {c.datasourceName && (
            <span className="shrink-0 text-[10px] text-muted-foreground">{c.datasourceName}</span>
          )}
          {c.layer && (
            <span className="shrink-0 rounded bg-muted px-1 py-0.5 text-[10px] text-muted-foreground">
              {c.layer}
            </span>
          )}
          <span className="ml-auto shrink-0 text-[10px] text-muted-foreground">{c.type}</span>
        </button>
      ))}
    </div>
  )
}
