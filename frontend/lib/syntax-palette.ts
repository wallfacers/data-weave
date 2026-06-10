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
 * 调色板从主题的 emerald primary + taupe 中性阶派生：
 *   keyword 用品牌 emerald（最高频 token，锚定主题身份），辅以 blue(func)/
 *   amber(string)/cyan(number)/teal(type)/violet(constant)/rose(regexp) 作低饱和点缀，
 *   comment/operator 走 taupe 灰阶。
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

// 亮色：近黑前景 + taupe muted 底，emerald 锚定 keyword
const LIGHT: Palette = {
  fg: "#1c1b17",
  bg: "#f2f1ec",
  comment: "#8a857a",
  keyword: "#047857",
  string: "#a16207",
  number: "#0e7490",
  func: "#2563eb",
  type: "#0f766e",
  variable: "#26241d",
  constant: "#7c3aed",
  operator: "#6b6862",
  regexp: "#be123c",
}

// 暗色：近白前景 + taupe 深灰底，emerald 提亮避免刺眼
const DARK: Palette = {
  fg: "#e9e7df",
  bg: "#27261f",
  comment: "#857f74",
  keyword: "#34d399",
  string: "#d6b06a",
  number: "#56cfe1",
  func: "#7aa2f7",
  type: "#5eead4",
  variable: "#d7d4cb",
  constant: "#c4a7f5",
  operator: "#a39d92",
  regexp: "#fb7185",
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
      // 括号对配色（Monaco 默认彩虹）统一压成 operator 灰，避免与 emerald 主题冲突
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
