import Link from "next/link"
import { HugeiconsIcon } from "@hugeicons/react"
import { AiChat01Icon } from "@hugeicons/core-free-icons"

import { Button } from "@/components/ui/button"

export function ComingSoon({
  title,
  description,
}: {
  title: string
  description: string
}) {
  return (
    <div className="flex flex-1 flex-col items-center justify-center gap-4 p-10 text-center">
      <h1 className="text-2xl font-semibold tracking-tight">{title}</h1>
      <p className="max-w-md text-muted-foreground">{description}</p>
      <p className="text-sm text-muted-foreground">
        MVP 阶段，本模块的操作统一通过 Agent 对话完成。
      </p>
      <Button variant="outline" nativeButton={false} render={<Link href="/agent" />}>
        <HugeiconsIcon icon={AiChat01Icon} data-icon="inline-start" />
        去 Agent 对话
      </Button>
    </div>
  )
}
