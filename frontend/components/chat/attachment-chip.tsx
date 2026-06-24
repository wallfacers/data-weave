/**
 * 附件胶囊（chat-attachments）：实体引用 / 上传文件的统一展示。
 * - 只读态（消息气泡下）：图标 + 标签。
 * - 可移除态（输入框组合区）：尾部加 × 移除按钮。
 */
"use client"

import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  Alert02Icon,
  Cancel01Icon,
  CpuIcon,
  Database01Icon,
  File01Icon,
  TaskDaily01Icon,
} from "@hugeicons/core-free-icons"
import type { IconSvgElement } from "@hugeicons/react"

import { cn } from "@/lib/utils"
import type { ChatAttachment, EntityRefType } from "@/lib/chat/types"

const ENTITY_ICON: Record<EntityRefType, IconSvgElement> = {
  task: TaskDaily01Icon,
  instance: CpuIcon,
  finding: Alert02Icon,
  datasource: Database01Icon,
}

/** 字节数转人类可读（附件大小）。 */
export function humanSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

function chipLabel(a: ChatAttachment): string {
  return a.kind === "file" ? a.name : a.label
}

function chipIcon(a: ChatAttachment): IconSvgElement {
  return a.kind === "file" ? File01Icon : ENTITY_ICON[a.refType]
}

export function AttachmentChip({
  attachment,
  onRemove,
}: {
  attachment: ChatAttachment
  onRemove?: () => void
}) {
  const t = useTranslations("chat")
  return (
    <span
      className={cn(
        "flex min-w-0 items-center gap-1 rounded-full border bg-muted/60 py-0.5 pl-2 text-xs text-muted-foreground",
        onRemove ? "pr-1" : "pr-2",
      )}
    >
      <HugeiconsIcon icon={chipIcon(attachment)} className="size-3 shrink-0" />
      <span className="max-w-[12rem] truncate">{chipLabel(attachment)}</span>
      {attachment.kind === "file" && (
        <span className="shrink-0 text-muted-foreground/70">{humanSize(attachment.size)}</span>
      )}
      {onRemove && (
        <button
          type="button"
          onClick={onRemove}
          aria-label={t("removeAttachment")}
          className="flex size-4 shrink-0 items-center justify-center rounded-full hover:bg-background hover:text-foreground"
        >
          <HugeiconsIcon icon={Cancel01Icon} className="size-3" />
        </button>
      )}
    </span>
  )
}
