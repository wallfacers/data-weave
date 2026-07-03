import { type IconSvgElement } from "@hugeicons/react"
import { HugeiconsIcon } from "@hugeicons/react"

/**
 * 资源/健康度进度条——Worker 节点资源使用同款。
 *
 * 适用场景：CPU/内存/磁盘使用率，数据新鲜度健康占比等百分比指标。
 *
 * - value: 0-100 的百分比值（自动 clamp）
 * - threshold: 触发警告色的阈值（默认 90）
 * - highIsBad: true=高于阈值告警（资源使用），false=低于阈值告警（健康度）
 * - icon: 可选 hugeicons 图标
 */
export function ResourceBar({
  label,
  value,
  icon,
  threshold = 90,
  highIsBad = true,
  formatValue = (pct) => `${pct.toFixed(1)}%`,
}: {
  label: string
  value: number
  icon?: IconSvgElement
  threshold?: number
  highIsBad?: boolean
  formatValue?: (pct: number) => string
}) {
  const pct = Math.min(100, Math.max(0, value))
  const isAlert = highIsBad ? pct >= threshold : pct < threshold

  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex items-center justify-between text-xs font-sans">
        <span className="flex items-center gap-1.5 text-muted-foreground">
          {icon && <HugeiconsIcon icon={icon} className="size-3.5" />}
          {label}
        </span>
        <span
          className={isAlert ? "font-medium text-destructive" : "text-foreground"}
        >
          {formatValue(pct)}
        </span>
      </div>
      <div className="h-1.5 w-full overflow-hidden rounded-full bg-muted">
        <div
          className={`h-full rounded-full transition-all ${
            isAlert ? "bg-destructive" : "bg-primary"
          }`}
          style={{ width: `${pct}%` }}
        />
      </div>
    </div>
  )
}
