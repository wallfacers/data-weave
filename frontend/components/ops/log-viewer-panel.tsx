"use client"

import { useCallback, useEffect, useRef, useState } from "react"
import { useLocale, useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import { Download01Icon } from "@hugeicons/core-free-icons"
import { OverlayScrollbarsComponent } from "overlayscrollbars-react"
import type { OverlayScrollbarsComponentRef } from "overlayscrollbars-react"

import { Button } from "@/components/ui/button"
import { API_BASE, authFetch, type ApiResponse } from "@/lib/types"
import { useFormatDateTime } from "@/hooks/use-format-date-time"

const CHUNK_SIZE = 65536

export interface SidePanelViewProps {
  params?: Record<string, unknown>
  onClose: () => void
}

export function LogViewerPanel({ params, onClose }: SidePanelViewProps) {
  const t = useTranslations("logViewer")
  const locale = useLocale()
  const formatDateTime = useFormatDateTime()
  const instanceId = params?.instanceId as number
  const taskId = params?.taskId as number | undefined
  const startedAt = params?.startedAt as string | undefined
  const finishedAt = params?.finishedAt as string | undefined

  const [content, setContent] = useState("")
  const [totalSize, setTotalSize] = useState(0)
  const [loading, setLoading] = useState(true)
  const osRef = useRef<OverlayScrollbarsComponentRef>(null)

  const fetchLog = useCallback(async (offset: number) => {
    try {
      const res = await authFetch(`${API_BASE}/api/ops/instances/${instanceId}/log?offset=${offset}&limit=${CHUNK_SIZE}`)
      if (!res.ok) return
      const json = (await res.json()) as ApiResponse<{ totalSize: number; content: string; hasMore: boolean }>
      const data = json.data
      if (!data) return
      setTotalSize(data.totalSize)
      setContent((prev) => prev + (data.content || ""))
      return data.hasMore
    } finally {
      setLoading(false)
    }
  }, [instanceId])

  useEffect(() => {
    setContent("")
    setLoading(true)
    fetchLog(0)
  }, [instanceId, fetchLog])

  useEffect(() => {
    const vp = osRef.current?.osInstance()?.elements().viewport
    if (vp) vp.scrollTop = vp.scrollHeight
  }, [content])

  async function loadMore() {
    setLoading(true)
    await fetchLog(content.length)
  }

  function download() {
    const blob = new Blob([content], { type: "text/plain" })
    const url = URL.createObjectURL(blob)
    const a = document.createElement("a")
    a.href = url
    a.download = `instance-${instanceId}-log.txt`
    a.click()
    URL.revokeObjectURL(url)
  }

  return (
    <div className="flex flex-1 flex-col gap-3 overflow-hidden p-4">
      <div className="flex items-center gap-3 text-xs text-muted-foreground">
        <span className="rounded-md bg-muted px-2 py-0.5 font-mono">
          {t("bytes", { size: totalSize.toLocaleString(locale) })}
        </span>
        {taskId && <span>{t("taskLabel", { id: taskId })}</span>}
        {startedAt && <span>{t("startedLabel", { time: formatDateTime(startedAt) })}</span>}
        {finishedAt && <span>{t("finishedLabel", { time: formatDateTime(finishedAt) })}</span>}
      </div>

      <OverlayScrollbarsComponent
        ref={osRef}
        element="div"
        className="flex-1 rounded-md border bg-muted/50"
        options={{ scrollbars: { theme: "os-theme-dark", autoHide: "never" } }}
      >
        <pre className="p-3 font-mono text-xs leading-relaxed whitespace-pre-wrap">
          {content || (loading ? t("loading") : t("emptyLog"))}
        </pre>
      </OverlayScrollbarsComponent>

      <div className="flex items-center gap-2">
        {content.length < totalSize && (
          <Button variant="outline" size="sm" onClick={loadMore} disabled={loading}>
            {t("loadMore")}
          </Button>
        )}
        <Button variant="outline" size="sm" onClick={download}>
          <HugeiconsIcon icon={Download01Icon} className="size-3.5" />
          {t("download")}
        </Button>
      </div>
    </div>
  )
}
