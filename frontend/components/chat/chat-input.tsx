/**
 * 聊天输入框（自研 composer，对齐 workhorse-assistant 观感）：
 * 上 textarea（自适应高度 ≤200px）+ 附件 chip 行 + 下工具栏行（实时通道状态点 /「+」附件 / 可开关上下文胶囊 / 发送·停止）。
 * - IME 守卫：中文等输入法选字态按 Enter 不误发送（workhorse 同款 isComposing 拦截）。
 * - 上下文胶囊：基于真实 AgentPageContext，点 × 可不附带、点「附带」可恢复，发送时透传 forwardedProps。
 * - 真附件：「+」拉起选择器，可上传文件（POST /api/chat/files）或引用平台实体（任务/实例/发现/数据源），
 *   随消息经 forwardedProps.dataweave.attachments 送达后端。
 */
"use client"

import { useCallback, useEffect, useMemo, useRef, useState } from "react"
import { useTranslations } from "next-intl"
import { toast } from "sonner"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  Add01Icon,
  Alert02Icon,
  ArrowUp01Icon,
  Attachment01Icon,
  Cancel01Icon,
  CpuIcon,
  Database01Icon,
  File01Icon,
  Layers01Icon,
  Loading03Icon,
  StopIcon,
  TaskDaily01Icon,
} from "@hugeicons/core-free-icons"
import type { IconSvgElement } from "@hugeicons/react"

import { cn } from "@/lib/utils"
import { useChatStore } from "@/lib/chat/store"
import { chatProvider } from "@/lib/chat/provider"
import { DwScroll } from "@/components/ui/dw-scroll"
import type {
  AgentPageContext,
  ChatAttachment,
  EntityOption,
  EntityRefType,
} from "@/lib/chat/types"
import { AttachmentChip } from "./attachment-chip"

function hasContext(c?: AgentPageContext): c is AgentPageContext {
  return !!c && !!(c.instanceId || c.nodeId || c.taskId || c.module || c.pathname)
}

/** ID 截断（… 表示裁剪内容，符合规范：仅 ID 截断允许省略号）。 */
function shortId(s: string): string {
  return s.length > 10 ? `${s.slice(0, 8)}…` : s
}

const ENTITY_TABS: { type: EntityRefType; icon: IconSvgElement; key: string }[] = [
  { type: "task", icon: TaskDaily01Icon, key: "entityTask" },
  { type: "instance", icon: CpuIcon, key: "entityInstance" },
  { type: "finding", icon: Alert02Icon, key: "entityFinding" },
  { type: "datasource", icon: Database01Icon, key: "entityDatasource" },
]

/** 单文件上限 10MB（与后端 ChatFileService.MAX_BYTES 一致）。 */
const MAX_FILE_BYTES = 10 * 1024 * 1024

const attKey = (a: ChatAttachment): string =>
  a.kind === "file" ? `file:${a.fileId}` : `entity:${a.refType}:${a.refId}`

export function ChatInput({ context }: { context?: AgentPageContext }) {
  const t = useTranslations("chat")
  const send = useChatStore((s) => s.sendMessage)
  const cancel = useChatStore((s) => s.cancel)
  const activeId = useChatStore((s) => s.activeId)
  const streamConnected = useChatStore((s) => s.streamConnected)
  const isStreaming = useChatStore((s) =>
    activeId ? (s.runtimes[activeId]?.streaming.size ?? 0) > 0 : false,
  )

  const ctx = hasContext(context) ? context : undefined
  const ctxKey = ctx
    ? `${ctx.instanceId ?? ""}|${ctx.nodeId ?? ""}|${ctx.taskId ?? ""}|${ctx.module ?? ""}|${ctx.pathname ?? ""}`
    : ""
  const [text, setText] = useState("")
  // 记录被用户「移除」的上下文键；上下文一变键即不同 → attach 自动恢复（免 effect 重置 state）。
  const [detachedKey, setDetachedKey] = useState<string | null>(null)
  const attach = !!ctx && detachedKey !== ctxKey
  const [attachments, setAttachments] = useState<ChatAttachment[]>([])
  const taRef = useRef<HTMLTextAreaElement>(null)
  const fileRef = useRef<HTMLInputElement>(null)

  // 附件选择器
  const [pickerOpen, setPickerOpen] = useState(false)
  const [tab, setTab] = useState<EntityRefType>("task")
  const [query, setQuery] = useState("")
  const [options, setOptions] = useState<EntityOption[]>([])
  const [searching, setSearching] = useState(false)
  const [uploading, setUploading] = useState(false)
  const pickerRef = useRef<HTMLDivElement>(null)
  const plusRef = useRef<HTMLButtonElement>(null)

  // 自适应高度（上限 200px）。
  useEffect(() => {
    const ta = taRef.current
    if (!ta) return
    ta.style.height = "auto"
    ta.style.height = `${Math.min(ta.scrollHeight, 200)}px`
  }, [text])

  // 选择器外部点击关闭（排除面板自身与 + 按钮，避免点了又被触发器重开 / 点选项即关）。
  useEffect(() => {
    if (!pickerOpen) return
    const onDown = (e: MouseEvent) => {
      const target = e.target as Node
      if (pickerRef.current?.contains(target) || plusRef.current?.contains(target)) return
      setPickerOpen(false)
    }
    document.addEventListener("mousedown", onDown)
    return () => document.removeEventListener("mousedown", onDown)
  }, [pickerOpen])

  // 实体搜索（tab/query 变化时拉取，含防抖）。setState 放进异步回调，避免 effect 体内同步 setState。
  useEffect(() => {
    if (!pickerOpen) return
    let alive = true
    const handle = setTimeout(() => {
      setSearching(true)
      chatProvider
        .searchEntities(tab, query)
        .then((res) => {
          if (alive) setOptions(res)
        })
        .catch(() => {
          if (alive) setOptions([])
        })
        .finally(() => {
          if (alive) setSearching(false)
        })
    }, 180)
    return () => {
      alive = false
      clearTimeout(handle)
    }
  }, [pickerOpen, tab, query])

  const ctxChip = useMemo(() => {
    if (!ctx) return null
    if (ctx.instanceId) return { icon: TaskDaily01Icon, label: t("ctxInstance", { id: shortId(ctx.instanceId) }) }
    if (ctx.taskId) return { icon: TaskDaily01Icon, label: t("ctxTask", { id: shortId(ctx.taskId) }) }
    if (ctx.nodeId) return { icon: CpuIcon, label: t("ctxNode", { id: ctx.nodeId }) }
    return { icon: Layers01Icon, label: ctx.module ?? t("ctxView") }
  }, [ctx, t])

  const addAttachment = useCallback((a: ChatAttachment) => {
    setAttachments((prev) =>
      prev.some((p) => attKey(p) === attKey(a)) ? prev : [...prev, a],
    )
  }, [])

  const removeAttachment = useCallback((key: string) => {
    setAttachments((prev) => prev.filter((p) => attKey(p) !== key))
  }, [])

  const pickEntity = useCallback(
    (o: EntityOption) => {
      addAttachment({ kind: "entity", refType: o.refType, refId: o.refId, label: o.label })
      setPickerOpen(false)
      setQuery("")
    },
    [addAttachment],
  )

  const onFiles = useCallback(
    async (files: FileList | null) => {
      if (!files || files.length === 0) return
      // 前端预检文件大小，避免大文件在上传中途才发现超限。
      for (const file of Array.from(files)) {
        if (file.size > MAX_FILE_BYTES) {
          toast.error(t("fileTooLarge", { max: "10MB" }))
          if (fileRef.current) fileRef.current.value = ""
          return
        }
      }
      setPickerOpen(false)
      setUploading(true)
      try {
        for (const file of Array.from(files)) {
          const ref = await chatProvider.uploadFile(file)
          addAttachment({
            kind: "file",
            fileId: ref.id,
            name: ref.name,
            size: ref.size,
            mime: ref.mime,
          })
        }
      } catch {
        toast.error(t("uploadFailed"))
      } finally {
        setUploading(false)
        if (fileRef.current) fileRef.current.value = ""
      }
    },
    [addAttachment, t],
  )

  const submit = useCallback(() => {
    const v = text.trim()
    if ((!v && attachments.length === 0) || isStreaming) return
    setText("")
    const sent = attachments
    setAttachments([])
    void send(v, attach ? ctx : undefined, sent.length ? sent : undefined)
  }, [text, attachments, isStreaming, send, attach, ctx])

  const canSend = (text.trim().length > 0 || attachments.length > 0) && !isStreaming

  return (
    <div className="shrink-0 p-3">
      <div className="relative flex flex-col gap-2 rounded-xl border bg-card px-3 py-2.5 focus-within:ring-1 focus-within:ring-ring">
        <textarea
          ref={taRef}
          value={text}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => {
            if (e.nativeEvent.isComposing) return // 输入法选字态不发送（CJK）
            if (e.key === "Enter" && !e.shiftKey) {
              e.preventDefault()
              submit()
            }
          }}
          placeholder={t("placeholder")}
          rows={1}
          className="dw-textarea-thumb max-h-[200px] min-h-[1.5rem] w-full resize-none bg-transparent mr-2 pr-2 text-sm leading-relaxed outline-none placeholder:text-muted-foreground"
        />

        {/* 附件 chip 行 */}
        {(attachments.length > 0 || uploading) && (
          <div className="flex flex-wrap items-center gap-1.5">
            {attachments.map((a) => (
              <AttachmentChip key={attKey(a)} attachment={a} onRemove={() => removeAttachment(attKey(a))} />
            ))}
            {uploading && (
              <span className="flex items-center gap-1 rounded-full border bg-muted/60 px-2 py-0.5 text-xs text-muted-foreground">
                <HugeiconsIcon icon={Loading03Icon} className="size-3 animate-spin" />
                {t("uploading")}
              </span>
            )}
          </div>
        )}

        {/* 工具栏行 */}
        <div className="flex items-center justify-between gap-2">
          <div className="flex min-w-0 items-center gap-2">
            {/* 实时通道（主动开口）状态点 */}
            <span
              title={streamConnected ? t("streamLive") : t("streamOffline")}
              className={cn(
                "size-1.5 shrink-0 rounded-full",
                streamConnected ? "animate-pulse bg-success" : "bg-muted-foreground/40",
              )}
            />
            {/* 「+」附件选择器触发 */}
            <button
              ref={plusRef}
              type="button"
              onClick={() => setPickerOpen((v) => !v)}
              aria-label={t("addAttachment")}
              title={t("addAttachment")}
              className={cn(
                "flex size-6 shrink-0 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-muted hover:text-foreground",
                pickerOpen && "bg-muted text-foreground",
              )}
            >
              <HugeiconsIcon icon={Attachment01Icon} className="size-4" />
            </button>
            {/* 上下文胶囊：附带态可移除、移除后可恢复 */}
            {ctxChip && attach ? (
              <span className="flex min-w-0 items-center gap-1 rounded-full border bg-muted/60 py-0.5 pr-1 pl-2 text-xs text-muted-foreground">
                <HugeiconsIcon icon={ctxChip.icon} className="size-3 shrink-0" />
                <span className="truncate">{ctxChip.label}</span>
                <button
                  type="button"
                  onClick={() => setDetachedKey(ctxKey)}
                  aria-label={t("detachContext")}
                  className="flex size-4 shrink-0 items-center justify-center rounded-full hover:bg-background hover:text-foreground"
                >
                  <HugeiconsIcon icon={Cancel01Icon} className="size-3" />
                </button>
              </span>
            ) : ctxChip ? (
              <button
                type="button"
                onClick={() => setDetachedKey(null)}
                aria-label={t("attachContext")}
                className="flex items-center gap-1 rounded-full border border-dashed px-2 py-0.5 text-xs text-muted-foreground transition-colors hover:bg-muted/50 hover:text-foreground"
              >
                <HugeiconsIcon icon={Add01Icon} className="size-3" />
                <span>{t("attachContext")}</span>
              </button>
            ) : null}
          </div>

          {isStreaming ? (
            <button
              type="button"
              onClick={cancel}
              aria-label={t("stop")}
              className="flex size-8 shrink-0 items-center justify-center rounded-lg text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
            >
              <HugeiconsIcon icon={StopIcon} className="size-4" />
            </button>
          ) : (
            <button
              type="button"
              onClick={submit}
              disabled={!canSend}
              aria-label={t("send")}
              className="flex size-8 shrink-0 items-center justify-center rounded-lg bg-primary text-primary-foreground transition-opacity disabled:opacity-40"
            >
              <HugeiconsIcon icon={ArrowUp01Icon} className="size-4" />
            </button>
          )}
        </div>

        {/* 附件选择器面板 */}
        {pickerOpen && (
          <div
            ref={pickerRef}
            className="absolute bottom-full left-0 z-20 mb-2 w-80 overflow-hidden rounded-xl border bg-popover shadow-md"
          >
            {/* 上传文件 */}
            <button
              type="button"
              onClick={() => fileRef.current?.click()}
              className="flex w-full items-center gap-2 border-b px-3 py-2.5 text-sm transition-colors hover:bg-muted"
            >
              <HugeiconsIcon icon={File01Icon} className="size-4 text-muted-foreground" />
              <span>{t("attachFile")}</span>
            </button>

            {/* 实体 tab 行 */}
            <div className="flex items-center gap-1 px-2 pt-2">
              {ENTITY_TABS.map((e) => (
                <button
                  key={e.type}
                  type="button"
                  onClick={() => setTab(e.type)}
                  className={cn(
                    "flex flex-1 items-center justify-center gap-1 rounded-md px-1.5 py-1 text-xs transition-colors",
                    tab === e.type
                      ? "bg-primary/10 text-primary"
                      : "text-muted-foreground hover:bg-muted hover:text-foreground",
                  )}
                >
                  <HugeiconsIcon icon={e.icon} className="size-3.5 shrink-0" />
                  <span className="truncate">{t(e.key)}</span>
                </button>
              ))}
            </div>

            {/* 搜索 */}
            <div className="p-2">
              <input
                value={query}
                onChange={(ev) => setQuery(ev.target.value)}
                placeholder={t("searchEntity", { type: t(ENTITY_TABS.find((e) => e.type === tab)!.key) })}
                className="h-8 w-full rounded-md border bg-background px-2.5 text-sm outline-none focus:ring-1 focus:ring-ring placeholder:text-muted-foreground"
              />
            </div>

            {/* 结果 */}
            <DwScroll className="max-h-56" innerClassName="flex flex-col px-1 pb-2">
              {searching ? (
                <div className="flex items-center gap-2 px-2 py-3 text-xs text-muted-foreground">
                  <HugeiconsIcon icon={Loading03Icon} className="size-3.5 animate-spin" />
                  {t("searching")}
                </div>
              ) : options.length === 0 ? (
                <div className="px-2 py-3 text-xs text-muted-foreground">{t("noEntities")}</div>
              ) : (
                options.map((o) => {
                  const picked = attachments.some(
                    (a) => a.kind === "entity" && a.refType === o.refType && a.refId === o.refId,
                  )
                  return (
                    <button
                      key={`${o.refType}:${o.refId}`}
                      type="button"
                      disabled={picked}
                      onClick={() => pickEntity(o)}
                      className="flex items-center justify-between gap-2 rounded-md px-2 py-1.5 text-left text-sm transition-colors hover:bg-muted disabled:opacity-50"
                    >
                      <span className="min-w-0 truncate">{o.label}</span>
                      {o.hint && (
                        <span className="shrink-0 text-xs text-muted-foreground">{o.hint}</span>
                      )}
                    </button>
                  )
                })
              )}
            </DwScroll>
          </div>
        )}

        {/* 隐藏文件输入 */}
        <input
          ref={fileRef}
          type="file"
          multiple
          className="hidden"
          onChange={(e) => void onFiles(e.target.files)}
        />
      </div>
    </div>
  )
}
