/**
 * 资产检索查询状态构建/切换（029 US4，纯逻辑可单测）。
 *
 * 分面可过滤维度 = keyword / sensitivity / owner / tag + qualityMin（被动透传）。
 * **不含 status**：后端 `AssetSearchService` 无 status 入参且恒排除 RETIRED（analyze F1），
 * status 仅作只读计数展示,不参与查询构建。
 * qualityMin 为后端 v1 no-op（缺 022 评分卡表,analyze F2）,此处仅透传 + 前端静态声明。
 */

import type { AssetSearchParams } from "./catalog-api"

export interface AssetQueryState {
  keyword: string
  sensitivity: string
  owner: string
  tag: string
  qualityMin: string
  page: number
}

export const INITIAL_QUERY: AssetQueryState = {
  keyword: "",
  sensitivity: "",
  owner: "",
  tag: "",
  qualityMin: "",
  page: 1,
}

type FacetKey = "sensitivity" | "owner" | "tag"

/** 点选分面：再点同值取消（清空）,否则替换;任何切换都复位 page=1。 */
export function toggleFacet(state: AssetQueryState, key: FacetKey, value: string): AssetQueryState {
  const next = state[key] === value ? "" : value
  return { ...state, [key]: next, page: 1 }
}

export function setKeyword(state: AssetQueryState, keyword: string): AssetQueryState {
  return { ...state, keyword, page: 1 }
}

export function setQualityMin(state: AssetQueryState, qualityMin: string): AssetQueryState {
  return { ...state, qualityMin, page: 1 }
}

export function setPage(state: AssetQueryState, page: number): AssetQueryState {
  return { ...state, page }
}

/** 组装为后端搜索参数：省略空项;qualityMin 转数字（空/非数字省略）;不含 status。 */
export function buildSearchParams(state: AssetQueryState): AssetSearchParams {
  const p: AssetSearchParams = { page: state.page }
  if (state.keyword.trim()) p.keyword = state.keyword.trim()
  if (state.sensitivity) p.sensitivity = state.sensitivity
  if (state.owner) p.owner = state.owner
  if (state.tag) p.tag = state.tag
  const q = Number(state.qualityMin)
  if (state.qualityMin.trim() !== "" && Number.isFinite(q)) p.qualityMin = q
  return p
}
