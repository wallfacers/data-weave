/**
 * 指标上架载荷构建（029 US2，纯逻辑可单测）。
 *
 * 从「指标看板」选中的 `MetricCard`（GET /api/metrics）+ 表单输入,组装上架到市场的载荷。
 * 后端 `MetricListingService.list` 读 `{metricType, metricId, metricCode, description, freshnessInfo}`,
 * 其中 metricId 必填——未选卡片返回 null,调用方据此阻止提交。
 */

/** 指标看板卡片，镜像后端 MetricsController.MetricCard。 */
export interface MetricCard {
  id: number
  code: string
  name: string
  unit?: string
  versionNo?: number
  status?: string
  value?: unknown
}

/** 上架表单（指标定义之外的市场元信息）。 */
export interface ListingForm {
  metricType: string
  description: string
  freshnessInfo: string
}

export interface ListingPayload {
  metricId: number
  metricType: string
  metricCode?: string
  description?: string
  freshnessInfo?: string
}

/** 构建上架载荷；未选卡片（null）→ null。空白 description/freshnessInfo 省略。 */
export function buildListingPayload(card: MetricCard | null, form: ListingForm): ListingPayload | null {
  if (!card) return null
  const payload: ListingPayload = {
    metricId: card.id,
    metricType: form.metricType.trim() || "ATOMIC",
  }
  if (card.code) payload.metricCode = card.code
  const desc = form.description.trim()
  if (desc) payload.description = desc
  const fresh = form.freshnessInfo.trim()
  if (fresh) payload.freshnessInfo = fresh
  return payload
}
