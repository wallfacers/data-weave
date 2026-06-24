"use client"

import * as React from "react"
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  ChevronFirstIcon,
  ChevronLeftIcon,
  ChevronRightIcon,
  ChevronLastIcon,
} from "@hugeicons/core-free-icons"

import { cn } from "@/lib/utils"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { DropdownSelect } from "@/components/ui/select"

const DEFAULT_PAGE_SIZE_OPTIONS = [10, 20, 50, 100]

interface PaginationProps {
  page: number
  totalPages: number
  total: number
  size: number
  onPageChange: (page: number) => void
  onSizeChange?: (size: number) => void
  pageSizeOptions?: number[]
  className?: string
}

function Pagination({
  page,
  totalPages,
  total,
  size,
  onPageChange,
  onSizeChange,
  pageSizeOptions = DEFAULT_PAGE_SIZE_OPTIONS,
  className,
}: PaginationProps) {
  const t = useTranslations("ops")
  const [jumpValue, setJumpValue] = React.useState("")

  function handleJump(e: React.FormEvent) {
    e.preventDefault()
    const n = parseInt(jumpValue, 10)
    if (isNaN(n) || n < 1 || n > totalPages) return
    onPageChange(n)
    setJumpValue("")
  }

  const sizeOptions = pageSizeOptions.map((s) => ({
    value: String(s),
    label: String(s),
  }))

  return (
    <div
      className={cn(
        "flex items-center justify-between gap-3 px-1",
        className
      )}
    >
      {/* 左侧：每页条数 */}
      {onSizeChange && (
        <div className="flex items-center gap-1.5">
          <span className="text-xs text-muted-foreground">{t("pageSize")}</span>
          <DropdownSelect
            value={String(size)}
            onChange={(v) => onSizeChange(Number(v))}
            options={sizeOptions}
            triggerClassName="h-7 w-16"
          />
        </div>
      )}

      {/* 中间：页码信息 */}
      <span className="text-xs tabular-nums text-muted-foreground">
        {t("pageInfo", { total, page, totalPages })}
      </span>

      {/* 右侧：导航按钮 + 跳转 */}
      <div className="flex items-center gap-1">
        <Button
          variant="outline"
          size="sm"
          className="h-7 px-1.5"
          disabled={page <= 1}
          onClick={() => onPageChange(1)}
          title={t("pageFirst")}
        >
          <HugeiconsIcon icon={ChevronFirstIcon} className="size-3.5" />
        </Button>
        <Button
          variant="outline"
          size="sm"
          className="h-7 px-1.5"
          disabled={page <= 1}
          onClick={() => onPageChange(page - 1)}
          title={t("prevPage")}
        >
          <HugeiconsIcon icon={ChevronLeftIcon} className="size-3.5" />
        </Button>

        <span className="mx-1 text-xs tabular-nums text-muted-foreground min-w-[3ch] text-center">
          {page}
        </span>

        <Button
          variant="outline"
          size="sm"
          className="h-7 px-1.5"
          disabled={page >= totalPages}
          onClick={() => onPageChange(page + 1)}
          title={t("nextPage")}
        >
          <HugeiconsIcon icon={ChevronRightIcon} className="size-3.5" />
        </Button>
        <Button
          variant="outline"
          size="sm"
          className="h-7 px-1.5"
          disabled={page >= totalPages}
          onClick={() => onPageChange(totalPages)}
          title={t("pageLast")}
        >
          <HugeiconsIcon icon={ChevronLastIcon} className="size-3.5" />
        </Button>

        {totalPages > 5 && (
          <form onSubmit={handleJump} className="ml-1 flex items-center gap-1">
            <span className="text-xs text-muted-foreground">{t("pageJump")}</span>
            <Input
              className="h-7 w-12 text-xs tabular-nums"
              value={jumpValue}
              onChange={(e) => setJumpValue(e.target.value)}
              placeholder={`1-${totalPages}`}
            />
          </form>
        )}
      </div>
    </div>
  )
}

export { Pagination }
