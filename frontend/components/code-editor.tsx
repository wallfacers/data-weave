"use client"

import { useCallback, useEffect, useState } from "react"
import { useTheme } from "next-themes"
import Editor, { type BeforeMount } from "@monaco-editor/react"
import { shikiToMonaco } from "@shikijs/monaco"
import type { Highlighter } from "shiki"

import { getCodeHighlighter } from "@/lib/code-highlighter"
import { SYNTAX_LANGS, SYNTAX_THEME_NAMES } from "@/lib/syntax-palette"
import { cn } from "@/lib/utils"

export type CodeEditorLanguage = (typeof SYNTAX_LANGS)[number]

export type CodeEditorProps = {
  value: string
  onChange?: (value: string) => void
  language?: CodeEditorLanguage
  readOnly?: boolean
  height?: string | number
  className?: string
}

/**
 * 复用型代码编辑器（Monaco）。高亮由 Shiki 经 `shikiToMonaco` 接管，与 chat 代码块
 * 共用 `lib/syntax-palette` 的同一套主题对象，因此两端配色像素级一致。
 * 主题跟随 next-themes，亮/暗切换实时重设。
 *
 * 注意：`shikiToMonaco` 会用 Shiki 接管 Monaco 的整套主题系统，内建主题名
 * （`light`/`vs`/`vs-dark`）随之失效。因此必须：① highlighter 预加载完再渲染
 * Editor；② 在同步的 `beforeMount` 里先跑 `shikiToMonaco` 注册我们的主题；
 * ③ `theme` 始终传项目主题名 —— 否则 @monaco-editor/react 默认的 `theme="light"`
 * 会触发 “Theme `light` not found”。
 */
export function CodeEditor({
  value,
  onChange,
  language = "sql",
  readOnly = false,
  height = "100%",
  className,
}: CodeEditorProps) {
  const { resolvedTheme } = useTheme()
  const themeName =
    resolvedTheme === "dark" ? SYNTAX_THEME_NAMES.dark : SYNTAX_THEME_NAMES.light

  const [highlighter, setHighlighter] = useState<Highlighter | null>(null)
  useEffect(() => {
    let alive = true
    getCodeHighlighter().then((h) => {
      if (alive) setHighlighter(h)
    })
    return () => {
      alive = false
    }
  }, [])

  // highlighter 已就绪，beforeMount 同步把 Shiki 主题/语言注册进 Monaco，
  // 早于 Editor 应用 theme prop，从而 `light` 永不被应用。
  const handleBeforeMount = useCallback<BeforeMount>(
    (monaco) => {
      if (!highlighter) return
      const known = new Set(
        monaco.languages.getLanguages().map((l: { id: string }) => l.id),
      )
      for (const lang of SYNTAX_LANGS) {
        if (!known.has(lang)) monaco.languages.register({ id: lang })
      }
      shikiToMonaco(highlighter, monaco)
    },
    [highlighter],
  )

  const loading = (
    <div className="flex h-full items-center justify-center text-sm text-muted-foreground">
      加载编辑器…
    </div>
  )

  return (
    <div className={cn("h-full w-full overflow-hidden rounded-lg bg-muted", className)}>
      {highlighter ? (
        <Editor
          height={height}
          language={language}
          value={value}
          theme={themeName}
          beforeMount={handleBeforeMount}
          onChange={(v) => onChange?.(v ?? "")}
          loading={loading}
          options={{
            readOnly,
            fontSize: 13,
            fontFamily: "var(--font-mono)",
            fontLigatures: true,
            lineNumbersMinChars: 3,
            minimap: { enabled: false },
            // 关掉默认的彩虹括号配色（与 emerald 语法主题冲突），括号跟随 token 灰
            bracketPairColorization: { enabled: false },
            scrollBeyondLastLine: false,
            padding: { top: 12, bottom: 12 },
            renderLineHighlight: "line",
            smoothScrolling: true,
            tabSize: 2,
            automaticLayout: true,
            scrollbar: { verticalScrollbarSize: 8, horizontalScrollbarSize: 8 },
          }}
        />
      ) : (
        loading
      )}
    </div>
  )
}
