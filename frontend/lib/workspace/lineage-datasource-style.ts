/**
 * 054：数据源徽标配色 / 缩写工具（FR-007/011，US2 节点徽标 + 跨源边判定共用）。
 *
 * - {@link datasourceColor} 对 datasourceId 做确定性 hash → 从有限调色板（chart-1..5 语义 token，
 *   亮/暗自适应）取色；同 id 恒同色、不同 id 尽量异色。
 * - {@link datasourceAbbr} 取数据源展示名首段前两字符大写；当数据源数量超过调色板容量、配色重复时，
 *   以徽标缩写文本兜底保证可辨（FR-011/SC-003）。
 *
 * 纯函数、零副作用，便于 vitest 直接覆盖。
 */

/** 有限调色板（语义 chart token，复用主题既定色板，禁手写硬编码色值）。 */
const DATASOURCE_PALETTE = [
  "var(--color-chart-1)",
  "var(--color-chart-2)",
  "var(--color-chart-3)",
  "var(--color-chart-4)",
  "var(--color-chart-5)",
] as const;

/** 数据源未知 / 孤儿节点的中性弱化色（跨源判定 unknown 态共用）。 */
export const DATASOURCE_UNKNOWN_COLOR = "var(--color-muted-foreground)";

/** FNV-1a 32 位确定性字符串 hash → 无符号 int（同输入恒同输出，无 Math.random）。 */
function hashId(id: string): number {
  let h = 0x811c9dc5;
  for (let i = 0; i < id.length; i++) {
    h ^= id.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return h >>> 0;
}

/**
 * datasourceId → 稳定配色。空 / 缺省（孤儿 / 未登记 / METRIC）→ 中性弱化色。
 * 调色板耗尽时按 hash 取模循环（颜色可能重复），可辨性由 {@link datasourceAbbr} 文本兜底。
 */
export function datasourceColor(datasourceId: string | undefined | null): string {
  if (!datasourceId) return DATASOURCE_UNKNOWN_COLOR;
  return DATASOURCE_PALETTE[hashId(datasourceId) % DATASOURCE_PALETTE.length];
}

/**
 * 数据源展示名 → 短缩写（取首个字母数字段前两字符大写）。用于徽标文本 + 配色耗尽时的可辨兜底。
 * 例：mysql-prod→MY · hive-dw→HI · pg-bi→PG · ods_db→OD。空名兜底 "?"。
 */
export function datasourceAbbr(name: string | undefined | null): string {
  if (!name) return "?";
  const cleaned = name.trim();
  if (!cleaned) return "?";
  const seg = cleaned.split(/[^A-Za-z0-9]+/).find((s) => s.length > 0) ?? cleaned;
  return seg.slice(0, 2).toUpperCase();
}
