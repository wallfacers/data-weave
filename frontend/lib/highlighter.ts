/**
 * Shiki 高亮单例（chat 代码块）—— 适配 DataWeave 自定义语法主题。
 *
 * 用项目 SYNTAX_THEME（见 lib/syntax-palette.ts，与 Monaco 同源）作为 light/dark 双主题，
 * `defaultColor: false` 使 Shiki 每个令牌发 `--shiki-light`/`--shiki-dark` CSS 变量，
 * 主题切换无需重新高亮。用 JS RegExp 引擎（无 Oniguruma WASM 依赖，浏览器稳）。
 */
"use client"

import {
  createHighlighter,
  type BundledLanguage,
  type Highlighter,
} from "shiki"
import { createJavaScriptRegexEngine } from "shiki/engine/javascript"
import {
  SYNTAX_THEME_DARK,
  SYNTAX_THEME_LIGHT,
  SYNTAX_THEME_NAMES,
} from "@/lib/syntax-palette"

const LANGUAGES: BundledLanguage[] = [
  "javascript",
  "typescript",
  "jsx",
  "tsx",
  "python",
  "java",
  "bash",
  "json",
  "html",
  "xml",
  "css",
  "scss",
  "sql",
  "yaml",
  "toml",
  "markdown",
  "dockerfile",
  "diff",
]

let highlighterPromise: Promise<Highlighter> | null = null

function getHighlighter(): Promise<Highlighter> {
  if (!highlighterPromise) {
    highlighterPromise = createHighlighter({
      themes: [SYNTAX_THEME_LIGHT, SYNTAX_THEME_DARK],
      langs: LANGUAGES,
      engine: createJavaScriptRegexEngine(),
    }).catch((err) => {
      // 不缓存 rejected promise：一次瞬时加载失败不该永久禁用高亮。
      highlighterPromise = null
      throw err
    })
  }
  return highlighterPromise
}

function resolveLang(lang: string): BundledLanguage {
  const n = lang.toLowerCase().replace(/[^a-z0-9+#]/g, "")
  const aliases: Record<string, BundledLanguage> = {
    js: "javascript",
    ts: "typescript",
    sh: "bash",
    shell: "bash",
    zsh: "bash",
    yml: "yaml",
    md: "markdown",
    py: "python",
    rs: "rust",
    "c++": "cpp",
    cs: "csharp",
    golang: "go",
    kt: "kotlin",
    rb: "ruby",
    docker: "dockerfile",
  }
  const r = aliases[n] ?? (n as BundledLanguage)
  return LANGUAGES.includes(r) ? r : ("text" as BundledLanguage)
}

/** 高亮 code，返回带 --shiki-light/--shiki-dark 变量的 HTML（无内联 color）。 */
export async function highlightCode(lang: string, code: string): Promise<string> {
  const hl = await getHighlighter()
  return hl.codeToHtml(code, {
    lang: resolveLang(lang),
    themes: { light: SYNTAX_THEME_NAMES.light, dark: SYNTAX_THEME_NAMES.dark },
    defaultColor: false,
  })
}
