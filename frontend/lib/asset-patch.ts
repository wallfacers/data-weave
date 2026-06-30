/**
 * 资产编辑的 PATCH-diff（029 / FR-002 部分更新语义）。
 *
 * 后端 `AssetCatalogService.update` 用 `containsKey` 判定：含键=改、缺键=不改。
 * 故编辑只应提交与初值不同的字段,避免无意清空未触字段。
 */

/** 浅比较：基本类型相等;数组按元素序列比较;其余按 JSON。 */
function eq(a: unknown, b: unknown): boolean {
  if (a === b) return true
  if (Array.isArray(a) && Array.isArray(b)) {
    return a.length === b.length && a.every((v, i) => v === b[i])
  }
  if (a == null || b == null) return a == null && b == null
  if (typeof a === "object" && typeof b === "object") {
    return JSON.stringify(a) === JSON.stringify(b)
  }
  return false
}

/**
 * 返回 `current` 中与 `initial` 不同的字段（仅在 `fields` 白名单内）。
 * 用于编辑提交：结果即 PATCH body（只含改动键）。
 */
export function diffPatch<T extends Record<string, unknown>>(
  initial: T,
  current: T,
  fields: (keyof T)[],
): Partial<T> {
  const patch: Partial<T> = {}
  for (const k of fields) {
    if (!eq(initial[k], current[k])) patch[k] = current[k]
  }
  return patch
}
