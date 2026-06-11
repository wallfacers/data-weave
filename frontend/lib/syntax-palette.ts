import type { ThemeRegistration } from "shiki"

/**
 * 代码语法主题 —— DataWeave 的「项目语法调色板」单一真相源。
 *
 * 为什么放在 TS（具体色）而非 globals.css 的 CSS 变量：
 *   本调色板的两个消费者 —— Monaco 编辑器与 chat 的 Shiki 高亮 —— 都在 JS 侧消费，
 *   且 Monaco 主题只吃具体十六进制色（吃不了 `var(--x)`）。把调色板放 TS 让两端
 *   共用同一个 `buildSyntaxTheme()`，颜色像素级一致；这是对 DESIGN.md 早期
 *   「css-variables 主题」设想的刻意偏离（理由见 DESIGN.md「代码语法主题」一节）。
 *
 * 调色板从「茶墨」主题（chamo-theme）的茶墨 primary + 暖纸中性阶派生：
 *   keyword 用品牌茶墨（最高频 token，锚定主题身份），func 用钴蓝（与 --link 同家族），
 *   辅以金(string)/青蓝(number)/深青(type)/紫(constant)/玫红(regexp) 作点缀，
 *   comment/operator 走暖灰阶。oklch 目标值见 openspec design.md D5，经换算为 hex。
 */

/** 编辑器与 chat 共用的语言集 —— 同一套 grammar 保证两端高亮一致 */
export const SYNTAX_LANGS = [
  "sql",
  "python",
  "json",
  "typescript",
  "javascript",
  "bash",
] as const

export const SYNTAX_THEME_NAMES = {
  light: "dataweave-light",
  dark: "dataweave-dark",
} as const

type Palette = {
  fg: string
  bg: string
  comment: string
  keyword: string
  string: string
  number: string
  func: string
  type: string
  variable: string
  constant: string
  operator: string
  regexp: string
}

// 亮色：墨黑前景 + 暖纸浅底（较 muted 略提亮一档，贴 card 白），茶墨锚定 keyword
const LIGHT: Palette = {
  fg: "#191510", // oklch(0.20 0.012 75)
  bg: "#f8f5ef", // oklch(0.97 0.008 80)
  comment: "#81796d", // oklch(0.58 0.02 80)
  keyword: "#864e18", // oklch(0.48 0.10 60) 茶墨
  string: "#a37800", // oklch(0.60 0.13 85) 金
  number: "#0081a5", // oklch(0.55 0.13 220) 青蓝
  func: "#2858cd", // oklch(0.50 0.19 264) 钴蓝
  type: "#007475", // oklch(0.50 0.10 195) 深青
  variable: "#2d2821", // oklch(0.28 0.015 75)
  constant: "#794db6", // oklch(0.52 0.16 300) 紫
  operator: "#6e685f", // oklch(0.52 0.015 80)
  regexp: "#bb3a6d", // oklch(0.55 0.17 0) 玫红
}

// 暗色：暖白前景 + 深茶墨底（略深于 card，凹陷一档），keyword 提亮为驼金避免刺眼
const DARK: Palette = {
  fg: "#ebe7e2", // oklch(0.93 0.008 80)
  bg: "#17130f", // oklch(0.19 0.01 65)
  comment: "#8d8579", // oklch(0.62 0.02 75)
  keyword: "#dfad6d", // oklch(0.78 0.10 72) 驼金
  string: "#deb95c", // oklch(0.80 0.12 88)
  number: "#4ebede", // oklch(0.75 0.11 220)
  func: "#77a2fc", // oklch(0.72 0.14 264)
  type: "#50bfbe", // oklch(0.74 0.10 195)
  variable: "#dbd7d0", // oklch(0.88 0.01 80)
  constant: "#b191ea", // oklch(0.72 0.13 300)
  operator: "#a49d94", // oklch(0.70 0.015 75)
  regexp: "#ed86a7", // oklch(0.74 0.13 0)
}

function tokenColors(p: Palette) {
  return [
    {
      scope: ["comment", "punctuation.definition.comment", "string.comment"],
      settings: { foreground: p.comment, fontStyle: "italic" },
    },
    {
      scope: [
        "keyword",
        "keyword.control",
        "keyword.other",
        "storage",
        "storage.type",
        "storage.modifier",
        "entity.name.tag",
      ],
      settings: { foreground: p.keyword },
    },
    {
      scope: [
        "string",
        "string.quoted",
        "string.template",
        "constant.other.symbol",
      ],
      settings: { foreground: p.string },
    },
    {
      scope: ["constant.numeric", "constant.numeric.integer", "constant.numeric.float"],
      settings: { foreground: p.number },
    },
    {
      scope: [
        "constant.language",
        "constant.language.boolean",
        "constant.language.null",
        "support.constant",
        "variable.language",
      ],
      settings: { foreground: p.constant },
    },
    {
      scope: [
        "entity.name.function",
        "support.function",
        "meta.function-call",
        "variable.function",
      ],
      settings: { foreground: p.func },
    },
    {
      scope: [
        "entity.name.type",
        "entity.name.class",
        "support.type",
        "support.class",
        "entity.other.inherited-class",
      ],
      settings: { foreground: p.type },
    },
    {
      scope: [
        "variable",
        "variable.other",
        "variable.parameter",
        "variable.other.property",
        "support.variable.property",
        "meta.object-literal.key",
      ],
      settings: { foreground: p.variable },
    },
    {
      scope: ["entity.other.attribute-name", "entity.name.tag.yaml"],
      settings: { foreground: p.func },
    },
    {
      scope: [
        "keyword.operator",
        "punctuation",
        "punctuation.accessor",
        "punctuation.separator",
        "punctuation.terminator",
        "meta.brace",
      ],
      settings: { foreground: p.operator },
    },
    {
      scope: ["string.regexp", "constant.character.escape"],
      settings: { foreground: p.regexp },
    },
  ]
}

/** 由调色板构建一套 Shiki 主题（VSCode 主题形态，Monaco 与 Streamdown 均可消费） */
export function buildSyntaxTheme(mode: "light" | "dark"): ThemeRegistration {
  const p = mode === "dark" ? DARK : LIGHT
  return {
    name: SYNTAX_THEME_NAMES[mode],
    type: mode,
    colors: {
      "editor.background": p.bg,
      "editor.foreground": p.fg,
      // 当前行高亮：茶墨品牌色极淡底色，无边框（#RRGGBBAA，shikiToMonaco 不吃 rgba）
      "editor.lineHighlightBackground":
        mode === "dark" ? "#dfad6d18" : "#864e1814",
      "editor.lineHighlightBorder": "#00000000",
      // 括号对配色（Monaco 默认彩虹）统一压成 operator 灰，避免与茶墨主题冲突
      "editorBracketHighlight.foreground1": p.operator,
      "editorBracketHighlight.foreground2": p.operator,
      "editorBracketHighlight.foreground3": p.operator,
      "editorBracketHighlight.foreground4": p.operator,
      "editorBracketHighlight.foreground5": p.operator,
      "editorBracketHighlight.foreground6": p.operator,
      "editorBracketHighlight.unexpectedBracket.foreground": p.regexp,
    },
    fg: p.fg,
    bg: p.bg,
    tokenColors: tokenColors(p),
  }
}

export const SYNTAX_THEME_LIGHT = buildSyntaxTheme("light")
export const SYNTAX_THEME_DARK = buildSyntaxTheme("dark")

/** chat（Streamdown）双主题入参：稳定引用，避免每次 render 重建 highlighter */
export const CHAT_SHIKI_THEME = [SYNTAX_THEME_LIGHT, SYNTAX_THEME_DARK] as const
