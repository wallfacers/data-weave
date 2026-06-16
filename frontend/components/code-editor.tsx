"use client"

import { useCallback, useEffect, useRef, useState } from "react"
import { useTheme } from "next-themes"
import Editor, { type BeforeMount, type OnMount } from "@monaco-editor/react"
import { shikiToMonaco } from "@shikijs/monaco"
import type { Highlighter } from "shiki"
import { toast } from "sonner"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  Copy01Icon,
  ClipboardPasteIcon,
  TextAlignLeftIcon,
  Eraser01Icon,
  Search01Icon,
} from "@hugeicons/core-free-icons"

import { getCodeHighlighter } from "@/lib/code-highlighter"
import { SYNTAX_LANGS, SYNTAX_THEME_NAMES } from "@/lib/syntax-palette"
import {
  clipboardCaps,
  isFormattable,
  toolbarActions,
} from "@/lib/code-editor-actions"
import { Button } from "@/components/ui/button"
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from "@/components/ui/tooltip"
import { cn } from "@/lib/utils"

export type CodeEditorLanguage = (typeof SYNTAX_LANGS)[number]

type MonacoEditor = Parameters<OnMount>[0]

export type CodeEditorProps = {
  value: string
  onChange?: (value: string) => void
  language?: CodeEditorLanguage
  readOnly?: boolean
  height?: string | number
  className?: string
  /** 开启可选操作工具栏（复制/粘贴/格式化/清空/查找）。默认关闭，内联用法不受影响。 */
  toolbar?: boolean
}

/**
 * 复用型代码编辑器（Monaco）。高亮由 Shiki 经 `shikiToMonaco` 接管，与 chat 代码块
 * 共用 `lib/syntax-palette.ts` 的同一套主题对象，因此两端配色像素级一致。
 * 主题跟随 next-themes，亮/暗切换实时重设。
 *
 * 注意：`shikiToMonaco` 会用 Shiki 接管 Monaco 的整套主题系统，内建主题名
 * （`light`/`vs`/`vs-dark`）随之失效。因此必须：① highlighter 预加载完再渲染
 * Editor；② 在同步的 `beforeMount` 里先跑 `shikiToMonaco` 注册我们的主题；
 * ③ `theme` 始终传项目主题名 —— 否则 @monaco-editor/react 默认的 `theme="light"`
 * 会触发 “Theme `light` not found”。
 *
 * 工具栏（`toolbar`）为 opt-in：复制/查找无权限风险；粘贴经 `navigator.clipboard.readText`
 * 读剪贴板，浏览器拦截（无权限/非安全上下文/默认禁读）时优雅降级为提示改用 Ctrl+V，
 * 编辑器原生快捷键始终兜底。clipboard API 不存在时复制/粘贴置灰带 tooltip。
 */
export function CodeEditor({
  value,
  onChange,
  language = "sql",
  readOnly = false,
  height = "100%",
  className,
  toolbar = false,
}: CodeEditorProps) {
  const { resolvedTheme } = useTheme()
  const themeName =
    resolvedTheme === "dark" ? SYNTAX_THEME_NAMES.dark : SYNTAX_THEME_NAMES.light

  const editorRef = useRef<MonacoEditor | null>(null)
  const handleMount = useCallback<OnMount>((editor) => {
    editorRef.current = editor
  }, [])

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
    <div className={cn("flex h-full w-full flex-col overflow-hidden rounded-lg bg-muted", className)}>
      {toolbar && (
        <EditorToolbar
          editorRef={editorRef}
          value={value}
          onChange={onChange}
          language={language}
          readOnly={readOnly}
        />
      )}
      <div className="min-h-0 flex-1">
        {highlighter ? (
          <Editor
            height={height}
            language={language}
            value={value}
            theme={themeName}
            beforeMount={handleBeforeMount}
            onMount={handleMount}
            onChange={(v) => onChange?.(v ?? "")}
            loading={loading}
            options={{
              readOnly,
              fontSize: 13,
              fontFamily: "var(--font-mono)",
              fontLigatures: true,
              lineNumbersMinChars: 3,
              minimap: { enabled: false },
              // 关掉默认的彩虹括号配色（与语义语法主题冲突），括号跟随 token 灰
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
    </div>
  )
}

type ToolbarProps = {
  editorRef: React.RefObject<MonacoEditor | null>
  value: string
  onChange?: (value: string) => void
  language: string
  readOnly: boolean
}

/**
 * 编辑器操作工具栏。复制/查找始终可用；粘贴/格式化/清空仅可写时显示。
 * 剪贴板按钮按能力探测置灰；粘贴失败优雅降级提示 Ctrl+V（原生快捷键兜底）。
 */
function EditorToolbar({ editorRef, value, onChange, language, readOnly }: ToolbarProps) {
  const { canWrite, canRead } = clipboardCaps(
    typeof navigator === "undefined" ? undefined : navigator,
  )
  const canFormat = isFormattable(language)
  const actions = new Set(toolbarActions(readOnly))

  const handleCopy = useCallback(async () => {
    try {
      await navigator.clipboard.writeText(editorRef.current?.getValue() ?? value)
      toast.success("已复制全部内容")
    } catch {
      toast.error("复制失败，请用 Ctrl+C")
    }
  }, [editorRef, value])

  const handlePaste = useCallback(async () => {
    const editor = editorRef.current
    if (!editor) return
    try {
      const text = await navigator.clipboard.readText()
      const selection = editor.getSelection()
      if (!selection) return
      editor.executeEdits("toolbar-paste", [
        { range: selection, text, forceMoveMarkers: true },
      ])
      editor.focus()
    } catch {
      toast.info("浏览器拦截了读取剪贴板，请用 Ctrl+V")
    }
  }, [editorRef])

  const handleFormat = useCallback(() => {
    editorRef.current?.getAction("editor.action.formatDocument")?.run()
  }, [editorRef])

  const handleClear = useCallback(() => {
    const editor = editorRef.current
    if (!editor) return
    if (!window.confirm("确定清空编辑器内容？此操作不可撤销。")) return
    editor.setValue("")
    onChange?.("")
    editor.focus()
  }, [editorRef, onChange])

  const handleFind = useCallback(() => {
    editorRef.current?.getAction("actions.find")?.run()
  }, [editorRef])

  return (
    <TooltipProvider>
      <div className="flex items-center gap-0.5 border-b border-border px-1.5 py-1">
        {actions.has("copy") && (
          <ToolbarBtn
            icon={Copy01Icon}
            label={canWrite ? "复制全部" : "浏览器不支持写剪贴板（用 Ctrl+C）"}
            onClick={handleCopy}
            disabled={!canWrite}
          />
        )}
        {actions.has("paste") && (
          <ToolbarBtn
            icon={ClipboardPasteIcon}
            label={canRead ? "粘贴到光标处" : "浏览器不支持读剪贴板（用 Ctrl+V）"}
            onClick={handlePaste}
            disabled={!canRead}
          />
        )}
        {actions.has("format") && (
          <ToolbarBtn
            icon={TextAlignLeftIcon}
            label={canFormat ? "格式化" : `${language} 无内建格式化`}
            onClick={handleFormat}
            disabled={!canFormat}
          />
        )}
        {actions.has("clear") && (
          <ToolbarBtn icon={Eraser01Icon} label="清空" onClick={handleClear} />
        )}
        {actions.has("find") && (
          <ToolbarBtn icon={Search01Icon} label="查找（Ctrl+F）" onClick={handleFind} />
        )}
      </div>
    </TooltipProvider>
  )
}

function ToolbarBtn({
  icon,
  label,
  onClick,
  disabled,
}: {
  icon: typeof Copy01Icon
  label: string
  onClick: () => void
  disabled?: boolean
}) {
  return (
    <Tooltip>
      <TooltipTrigger
        render={
          <Button
            type="button"
            size="icon"
            variant="ghost"
            className="size-7"
            onClick={onClick}
            disabled={disabled}
            aria-label={label}
          />
        }
      >
        <HugeiconsIcon icon={icon} className="size-4" />
      </TooltipTrigger>
      <TooltipContent>{label}</TooltipContent>
    </Tooltip>
  )
}
