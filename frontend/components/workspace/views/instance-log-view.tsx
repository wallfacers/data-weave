"use client"

import { useEffect, useRef } from "react"
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import { DocumentCodeIcon, RefreshIcon } from "@hugeicons/core-free-icons"
import { OverlayScrollbarsComponent, type OverlayScrollbarsComponentRef } from "overlayscrollbars-react"

import { useEventSource } from "@/lib/workspace/use-event-source"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { API_BASE } from "@/lib/types"

const OS_SCROLL_OPTIONS = {
  scrollbars: { theme: "os-theme-dark" as const, autoHide: "never" as const },
  overflow: { x: "hidden" as const, y: "scroll" as const },
}

interface InstanceLogViewProps {
  params?: Record<string, unknown>
}

export function InstanceLogView({ params }: InstanceLogViewProps) {
  const t = useTranslations("instanceLog")
  const instanceId = params?.instanceId as string | undefined
  const scrollRef = useRef<OverlayScrollbarsComponentRef>(null)
  const autoScrollRef = useRef(true)

  const { events, connected, error, clearEvents } = useEventSource(
    instanceId ? `${API_BASE}/api/ops/instances/${instanceId}/logs/stream` : "",
  )

  const getScrollEl = () => scrollRef.current?.getElement()

  // 自动滚动到底部
  useEffect(() => {
    if (autoScrollRef.current) {
      const el = getScrollEl()
      if (el) el.scrollTop = el.scrollHeight
    }
  }, [events])

  // 检测用户是否手动滚动
  const handleScroll = () => {
    const el = getScrollEl()
    if (!el) return
    autoScrollRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 50
  }

  if (!instanceId) {
    return (
      <div className="flex flex-1 items-center justify-center text-sm text-muted-foreground">
        {t("noInstanceId")}
      </div>
    )
  }

  const logLines = events.filter((e) => e.type === "log").map((e) => e.data)
  const isEnded = events.some((e) => e.type === "end")

  return (
    <div className="flex flex-1 flex-col gap-4 p-4">
      <div className="flex items-center gap-2">
        <HugeiconsIcon icon={DocumentCodeIcon} className="text-primary" />
        <h1 className="text-sm font-medium">{t("title")}</h1>
        <span className="font-mono text-xs text-muted-foreground">#{instanceId}</span>
        <Badge variant={connected ? "success" : error ? "destructive" : "info"} className="ml-auto">
          {connected ? t("live") : isEnded ? t("ended") : error ? t("disconnected") : t("connecting")}
        </Badge>
        <Button
          variant="ghost"
          size="sm"
          onClick={clearEvents}
          className="size-7 p-0"
          title={t("clear")}
        >
          <HugeiconsIcon icon={RefreshIcon} className="size-4" />
        </Button>
      </div>

      <OverlayScrollbarsComponent
        element="div"
        ref={scrollRef}
        onScroll={handleScroll}
        className="flex-1 rounded-lg border bg-muted/30 p-4 font-mono text-xs"
        options={OS_SCROLL_OPTIONS}
      >
        {logLines.length === 0 ? (
          <div className="text-muted-foreground">
            {connected ? t("waitingLogs") : isEnded ? t("noLogs") : t("connectingDots")}
          </div>
        ) : (
          <div className="space-y-0.5">
            {logLines.map((line, i) => (
              <div key={i} className="whitespace-pre-wrap break-all">
                {line}
              </div>
            ))}
          </div>
        )}
      </OverlayScrollbarsComponent>
    </div>
  )
}
