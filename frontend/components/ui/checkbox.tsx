"use client"

import * as React from "react"
import { HugeiconsIcon } from "@hugeicons/react"
import { CheckmarkSquareIcon } from "@hugeicons/core-free-icons"

import { cn } from "@/lib/utils"

/**
 * 简洁复选框：原生 input + 视觉方框，符合 shadcn 语义 token 风格。
 * 项目规则只禁止原生 `<select>`，复选框允许原生。
 */
interface CheckboxProps extends Omit<React.InputHTMLAttributes<HTMLInputElement>, "onChange"> {
  checked?: boolean
  onChange?: (checked: boolean) => void
}

export function Checkbox({ className, checked, onChange, ...props }: CheckboxProps) {
  return (
    <label
      className={cn(
        "inline-flex size-4 shrink-0 cursor-pointer items-center justify-center align-middle rounded border border-primary/40 bg-background transition-colors",
        checked && "bg-primary border-primary text-primary-foreground",
        className,
      )}
    >
      <input
        type="checkbox"
        className="sr-only"
        checked={checked}
        onChange={(e) => onChange?.(e.target.checked)}
        {...props}
      />
      {checked ? (
        <HugeiconsIcon icon={CheckmarkSquareIcon} className="size-3.5 text-primary-foreground" />
      ) : null}
    </label>
  )
}
