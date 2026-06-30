/**
 * 订阅判定纯逻辑（029 US3，可单测）。
 *
 * 资产详情内联订阅态需判断「当前资产是否已订阅」并取其 subId 以支持退订。
 * 订阅 targetType 区分 ASSET / METRIC——同 id 的 METRIC 订阅不可误判为资产订阅。
 */

import type { AssetSubscription } from "./catalog-api"

/** 在订阅列表中找当前资产（targetType=ASSET 且 targetId=assetId）的订阅;无则 null。 */
export function findAssetSubscription(
  list: AssetSubscription[] | null | undefined,
  assetId: number,
): AssetSubscription | null {
  if (!list) return null
  return list.find((s) => s.targetType === "ASSET" && s.targetId === assetId) ?? null
}
