"use client"

import { useCallback, useEffect, useRef, useState } from "react"
import { HugeiconsIcon } from "@hugeicons/react"
import { Cancel01Icon, Download01Icon } from "@hugeicons/core-free-icons"

import { Button } from "@/components/ui/button"
import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet"
import { type TaskInstance, formatDateTime, API_BASE } from "@/lib/types"

const CHUNK_SIZE = 65536

export function LogViewer({ instance, onClose }: { instance: TaskInstance; onClose: () => void }) {
  const [content, setContent] = useState("")
  const [totalSize, setTotalSize] = useState(0)
  const [loading, setLoading] = useState(true)
  const scrollRef = useRef<HTMLPreElement>(null)

  const fetchLog = useCallback(async (offset: number) => {
    try {
      const res = await fetch(`${API_BASE}/api/ops/instances/${instance.id}/log?offset=${offset}&limit=${CHUNK_SIZE}`)
      if (!res.ok) return
      const data = await res.json()
      setTotalSize(data.totalSize)
      setContent(prev => prev + (data.content || ""))
      return data.hasMore
    } finally {
      setLoading(false)
    }
  }, [instance.id])

  useEffect(() => {
    setContent("")
    setLoading(true)
    fetchLog(0)
  }, [instance.id, fetchLog])

  // Auto-scroll to bottom
  useEffect(() => {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight
    }
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
    a.download = `instance-${instance.id}-log.txt`
    a.click()
    URL.revokeObjectURL(url)
  }

  return (
    <Sheet open={true} onOpenChange={() => onClose()}>
      <SheetContent className="flex w-[640px] flex-col sm:max-w-[640px]">
        <SheetHeader>
          <SheetTitle className="flex items-center gap-2">
            <span>实例 #{instance.id} 日志</span>
            <span className="rounded-md bg-muted px-2 py-0.5 font-mono text-xs text-muted-foreground">
              {totalSize.toLocaleString()} bytes
            </span>
          </SheetTitle>
        </SheetHeader>

        <div className="mb-2 flex items-center gap-3 text-xs text-muted-foreground">
          <span>任务 #{instance.taskId}</span>
          <span>开始: {formatDateTime(instance.startedAt)}</span>
          <span>结束: {formatDateTime(instance.finishedAt)}</span>
        </div>

        <pre
          ref={scrollRef}
          className="flex-1 overflow-auto rounded-md border bg-muted/50 p-3 font-mono text-xs leading-relaxed whitespace-pre-wrap"
        >
          {content || (loading ? "加载中…" : "（无日志）")}
        </pre>

        <div className="flex items-center justify-between pt-2">
          <Button variant="outline" size="sm" onClick={download}>
            <HugeiconsIcon icon={Download01Icon} className="size-3.5" />
            下载
          </Button>
          <Button variant="outline" size="sm" onClick={onClose}>
            <HugeiconsIcon icon={Cancel01Icon} className="size-3.5" />
            关闭
          </Button>
        </div>
      </SheetContent>
    </Sheet>
  )
}
