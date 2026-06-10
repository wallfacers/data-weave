import { createHighlighter, type Highlighter } from "shiki"

import {
  SYNTAX_LANGS,
  SYNTAX_THEME_DARK,
  SYNTAX_THEME_LIGHT,
} from "./syntax-palette"

let singleton: Promise<Highlighter> | null = null

/**
 * 进程内单例 Shiki highlighter，预载项目语法主题与共用语言集。
 * 供 Monaco 编辑器经 `shikiToMonaco` 接线；与 chat 端的 Streamdown 共用同一套主题对象，
 * 保证编辑器与对话代码块高亮一致。
 */
export function getCodeHighlighter(): Promise<Highlighter> {
  if (!singleton) {
    singleton = createHighlighter({
      themes: [SYNTAX_THEME_LIGHT, SYNTAX_THEME_DARK],
      langs: [...SYNTAX_LANGS],
    })
  }
  return singleton
}
