/** 多会话切换器（Group 7.2）：当前会话标题 + 下拉列表（切换/新建/删除）。 */
"use client"

import { useEffect, useRef, useState } from "react"
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import { Add01Icon, Cancel01Icon, Chat01Icon } from "@hugeicons/core-free-icons"

import { useChatStore } from "@/lib/chat/store"

export function SessionSwitcher() {
  const t = useTranslations("chat")
  const sessions = useChatStore((s) => s.sessions)
  const activeId = useChatStore((s) => s.activeId)
  const switchSession = useChatStore((s) => s.switchSession)
  const newSession = useChatStore((s) => s.newSession)
  const deleteSession = useChatStore((s) => s.deleteSession)
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)

  // 外部点击关闭（排除面板自身）
  useEffect(() => {
    if (!open) return
    const onDown = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener("mousedown", onDown)
    return () => document.removeEventListener("mousedown", onDown)
  }, [open])

  const active = sessions.find((s) => s.id === activeId)
  const title = active?.title || t("untitledSession")

  return (
    <div ref={ref} className="relative">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        className="flex min-w-0 items-center gap-1 rounded-md px-1.5 py-1 text-xs text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
        title={title}
      >
        <HugeiconsIcon icon={Chat01Icon} className="size-3.5 shrink-0" />
        <span className="max-w-[7rem] truncate">{title}</span>
      </button>

      {open && (
        <div className="absolute left-0 top-full z-30 mt-1 w-56 rounded-[var(--radius-md)] border bg-card p-1 shadow-lg">
          <div className="flex items-center justify-between px-2 py-1">
            <span className="text-[11px] font-medium uppercase tracking-wide text-muted-foreground">
              {t("sessions")}
            </span>
            <button
              type="button"
              onClick={() => {
                void newSession()
                setOpen(false)
              }}
              title={t("newSession")}
              className="flex size-6 items-center justify-center rounded text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
            >
              <HugeiconsIcon icon={Add01Icon} className="size-3.5" />
            </button>
          </div>
          <div className="max-h-60 overflow-y-auto">
            {sessions.length === 0 ? (
              <p className="px-2 py-1.5 text-xs text-muted-foreground">
                {t("noSessions")}
              </p>
            ) : (
              sessions.map((s) => (
                <div
                  key={s.id}
                  className={`group flex items-center gap-1 rounded px-1 ${
                    s.id === activeId ? "bg-muted" : ""
                  }`}
                >
                  <button
                    type="button"
                    onClick={() => {
                      void switchSession(s.id)
                      setOpen(false)
                    }}
                    className="min-w-0 flex-1 truncate px-1.5 py-1.5 text-left text-xs hover:text-foreground"
                  >
                    {s.title || t("untitledSession")}
                  </button>
                  <button
                    type="button"
                    onClick={() => void deleteSession(s.id)}
                    title={t("deleteSession")}
                    aria-label={t("deleteSession")}
                    className="flex size-6 shrink-0 items-center justify-center rounded text-muted-foreground opacity-0 transition-opacity hover:bg-muted hover:text-destructive group-hover:opacity-100"
                  >
                    <HugeiconsIcon icon={Cancel01Icon} className="size-3.5" />
                  </button>
                </div>
              ))
            )}
          </div>
        </div>
      )}
    </div>
  )
}
