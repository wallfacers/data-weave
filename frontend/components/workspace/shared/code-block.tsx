"use client"

import { useEffect, useState } from "react"
import { HugeiconsIcon } from "@hugeicons/react"
import { RefreshIcon } from "@hugeicons/core-free-icons"
import { DwScroll } from "@/components/ui/dw-scroll"
import { highlightCode } from "@/lib/highlighter"

export function CodeBlock({ code, lang }: { code: string; lang: string }) {
  const [html, setHtml] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    highlightCode(lang, code).then((h) => {
      if (!cancelled) setHtml(h)
    })
    return () => { cancelled = true }
  }, [code, lang])

  if (html === null) {
    return (
      <span className="text-xs text-muted-foreground">
        <HugeiconsIcon icon={RefreshIcon} className="size-3.5 animate-spin inline mr-1" />
        Highlighting…
      </span>
    )
  }

  return (
    <DwScroll direction="both" className="max-h-64">
      <div
        className="text-xs [&_pre]:!bg-transparent [&_pre]:!p-3 [&_pre]:!m-0"
        dangerouslySetInnerHTML={{ __html: html }}
      />
    </DwScroll>
  )
}
