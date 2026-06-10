import { HugeiconsIcon } from "@hugeicons/react"
import { ArrowRight01Icon } from "@hugeicons/core-free-icons"

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
        MVP 阶段，本模块的操作统一通过右侧 Agent 对话完成。
      </p>
      <span className="inline-flex items-center gap-1 text-sm text-primary">
        打开右侧 Agent 面板
        <HugeiconsIcon icon={ArrowRight01Icon} className="size-4" />
      </span>
    </div>
  )
}
