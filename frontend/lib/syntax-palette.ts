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
 * 调色板分两类槽：**结构槽**（bg/fg/comment/operator/variable + editor.* 底色/前景/行高亮）
 *   对齐 `app/globals.css` 的**中性灰阶**（零彩度），与 app 灰主题无可见色温差；
 *   **语义槽**（keyword/string/number/func/type/constant/regexp）保留彩色，作为功能性
 *   语法着色不随 UI 主题转黑白（见 DESIGN.md「代码语法主题」）。func 用钴蓝（与 --link 同家族），
 *   辅以金(string)/青蓝(number)/深青(type)/紫(constant)/玫红(regexp)，keyword 用品牌强调色。
 *   灰阶 oklch 锚点见 openspec design.md D1，经换算为 hex。
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

// 亮色：结构槽走中性灰阶（零彩度，贴 globals.css），语义槽保留彩色
const LIGHT: Palette = {
  fg: "#171717", // oklch(0.205 0 0) 中性近黑
  bg: "#fafafa", // oklch(0.985 0 0) 中性浅灰底（凹于纯白 card）
  comment: "#737373", // oklch(0.556 0 0) muted-foreground 灰
  keyword: "#864e18", // oklch(0.48 0.10 60) 茶墨
  string: "#a37800", // oklch(0.60 0.13 85) 金
  number: "#0081a5", // oklch(0.55 0.13 220) 青蓝
  func: "#2858cd", // oklch(0.50 0.19 264) 钴蓝
  type: "#007475", // oklch(0.50 0.10 195) 深青
  variable: "#2e2e2e", // oklch(0.30 0 0) 中性深灰
  constant: "#794db6", // oklch(0.52 0.16 300) 紫
  operator: "#636363", // oklch(0.50 0 0) 中性灰
  regexp: "#bb3a6d", // oklch(0.55 0.17 0) 玫红
}

// 暗色：结构槽走中性灰阶（底色凹陷一档于 card 0.205），语义槽保留彩色并提亮避免刺眼
const DARK: Palette = {
  fg: "#e8e8e8", // oklch(0.93 0 0) 中性近白
  bg: "#121212", // oklch(0.18 0 0) 中性深灰底（凹于 card 0.205）
  comment: "#868686", // oklch(0.62 0 0) 中性灰
  keyword: "#dfad6d", // oklch(0.78 0.10 72) 驼金
  string: "#deb95c", // oklch(0.80 0.12 88)
  number: "#4ebede", // oklch(0.75 0.11 220)
  func: "#77a2fc", // oklch(0.72 0.14 264)
  type: "#50bfbe", // oklch(0.74 0.10 195)
  variable: "#d7d7d7", // oklch(0.88 0 0) 中性浅灰
  constant: "#b191ea", // oklch(0.72 0.13 300)
  operator: "#9e9e9e", // oklch(0.70 0 0) 中性灰
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
      // 当前行高亮：中性灰极淡底色，无边框（#RRGGBBAA，shikiToMonaco 不吃 rgba）
      "editor.lineHighlightBackground":
        mode === "dark" ? "#ffffff0e" : "#0000000a",
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
