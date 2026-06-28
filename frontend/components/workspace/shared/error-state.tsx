"use client"

import { useTranslations } from "next-intl"
import { Button } from "@/components/ui/button"

export function ErrorState({ message, onRetry }: { message: string; onRetry: () => void }) {
  const tc = useTranslations("common")
  return (
    <div className="flex flex-col items-center justify-center gap-3 py-12 text-muted-foreground">
      <span className="text-xs text-destructive">{message}</span>
      <Button variant="outline" size="sm" onClick={onRetry}>
        {tc("retry")}
      </Button>
    </div>
  )
}
