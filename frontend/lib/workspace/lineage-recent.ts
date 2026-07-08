/**
 * 054 US3：血缘「最近」分面——会话本地记录锚定过的资产（无 ownership、无后端）。
 *
 * sessionStorage 键 `dw.lineage.recent`，按最近优先、按 id 去重、上限 {@link MAX_RECENT}。
 * 纯函数式读写，SSR/无 storage/损坏 JSON 时静默回退空表；便于 vitest 直接覆盖。
 */

const STORAGE_KEY = "dw.lineage.recent"
export const MAX_RECENT = 12

export interface RecentAsset {
  id: string
  name: string
  type: string
  /** 所属数据源展示名（可空，用于同名跨库区分显示）。 */
  datasourceName?: string
}

function storage(): Storage | null {
  try {
    return typeof window !== "undefined" ? window.sessionStorage : null
  } catch {
    return null
  }
}

/** 读最近资产（最近优先）。损坏/缺失 → []。 */
export function getRecentAssets(): RecentAsset[] {
  const s = storage()
  if (!s) return []
  try {
    const raw = s.getItem(STORAGE_KEY)
    if (!raw) return []
    const parsed = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []
    return parsed.filter((x) => x && typeof x.id === "string" && typeof x.name === "string")
  } catch {
    return []
  }
}

/**
 * 记一次锚定：置顶、按 id 去重、截断至上限。返回更新后的列表（便于测试/即时刷新）。
 * 空 id 直接忽略。
 */
export function recordRecentAsset(asset: RecentAsset): RecentAsset[] {
  if (!asset || !asset.id) return getRecentAssets()
  const prev = getRecentAssets().filter((a) => a.id !== asset.id)
  const next = [
    { id: asset.id, name: asset.name, type: asset.type, datasourceName: asset.datasourceName },
    ...prev,
  ].slice(0, MAX_RECENT)
  const s = storage()
  if (s) {
    try {
      s.setItem(STORAGE_KEY, JSON.stringify(next))
    } catch {
      /* storage 不可用（隐私模式等）→ 静默 */
    }
  }
  return next
}

/** 清空最近（会话内）。 */
export function clearRecentAssets(): void {
  const s = storage()
  if (s) {
    try {
      s.removeItem(STORAGE_KEY)
    } catch {
      /* noop */
    }
  }
}
