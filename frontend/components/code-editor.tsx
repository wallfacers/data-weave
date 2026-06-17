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
} from "@/lib/code-editor-actions"
import {
  ContextMenu,
  ContextMenuTrigger,
  ContextMenuContent,
  ContextMenuItem,
  ContextMenuSeparator,
} from "@/components/ui/context-menu"
import { cn } from "@/lib/utils"

export type CodeEditorLanguage = (typeof SYNTAX_LANGS)[number]

type MonacoEditor = Parameters<OnMount>[0]
type Monaco = Parameters<OnMount>[1]

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
 * 共用 `lib/syntax-palette.ts` 的同一套主题对象，因此两端配色像素级一致。
 * 主题跟随 next-themes，亮/暗切换实时重设。
 *
 * 操作通过右键菜单 + 快捷键触发：
 * - 复制全部：Ctrl+Shift+C（Monaco 原生 Ctrl+C 仅复制选中）
 * - 粘贴：Ctrl+V（Monaco 原生）
 * - 格式化：Shift+Alt+F（Monaco 原生）
 * - 清空：Ctrl+Shift+Delete
 * - 查找：Ctrl+F（Monaco 原生）
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

  const editorRef = useRef<MonacoEditor | null>(null)
  const handleMount = useCallback<OnMount>((editor, monaco) => {
    editorRef.current = editor
    registerActions(editor, monaco, value, onChange, language)
  }, [value, onChange, language])

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
    <ContextMenu>
      <ContextMenuTrigger className={cn("flex h-full w-full flex-col overflow-hidden rounded-lg bg-muted", className)}>
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
                // addExtraSpaceOnTop 默认 true 会在 find widget 出现时插入额外行高，
                // 触发 automaticLayout ResizeObserver → 重算位置循环 → 悬停按钮时抖动
                find: { addExtraSpaceOnTop: false },
                // 禁用 Monaco 默认右键菜单，用我们自己的
                contextmenu: false,
              }}
            />
          ) : (
            loading
          )}
        </div>
      </ContextMenuTrigger>
      <ContextMenuContent>
        <EditorContextMenu
          editorRef={editorRef}
          value={value}
          onChange={onChange}
          language={language}
          readOnly={readOnly}
        />
      </ContextMenuContent>
    </ContextMenu>
  )
}

type ContextMenuProps = {
  editorRef: React.RefObject<MonacoEditor | null>
  value: string
  onChange?: (value: string) => void
  language: string
  readOnly: boolean
}

/**
 * 右键菜单内容。复制/查找始终可用；粘贴/格式化/清空仅可写时显示。
 * 剪贴板按钮按能力探测置灰；粘贴失败优雅降级提示 Ctrl+V（原生快捷键兜底）。
 */
function EditorContextMenu({ editorRef, value, onChange, language, readOnly }: ContextMenuProps) {
  const { canWrite, canRead } = clipboardCaps(
    typeof navigator === "undefined" ? undefined : navigator,
  )
  const canFormat = isFormattable(language)

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
      editor.executeEdits("context-paste", [
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
    <>
      <ContextMenuItem onClick={handleCopy} disabled={!canWrite}>
        <HugeiconsIcon icon={Copy01Icon} className="size-4" />
        复制全部
        <span className="ml-auto text-xs text-muted-foreground">Ctrl+Shift+C</span>
      </ContextMenuItem>
      {!readOnly && (
        <ContextMenuItem onClick={handlePaste} disabled={!canRead}>
          <HugeiconsIcon icon={ClipboardPasteIcon} className="size-4" />
          粘贴
          <span className="ml-auto text-xs text-muted-foreground">Ctrl+V</span>
        </ContextMenuItem>
      )}
      {!readOnly && canFormat && (
        <>
          <ContextMenuSeparator />
          <ContextMenuItem onClick={handleFormat}>
            <HugeiconsIcon icon={TextAlignLeftIcon} className="size-4" />
            格式化
            <span className="ml-auto text-xs text-muted-foreground">Shift+Alt+F</span>
          </ContextMenuItem>
        </>
      )}
      <ContextMenuSeparator />
      <ContextMenuItem onClick={handleFind}>
        <HugeiconsIcon icon={Search01Icon} className="size-4" />
        查找
        <span className="ml-auto text-xs text-muted-foreground">Ctrl+F</span>
      </ContextMenuItem>
      {!readOnly && (
        <>
          <ContextMenuSeparator />
          <ContextMenuItem onClick={handleClear} variant="destructive">
            <HugeiconsIcon icon={Eraser01Icon} className="size-4" />
            清空
            <span className="ml-auto text-xs text-muted-foreground">Ctrl+Shift+Del</span>
          </ContextMenuItem>
        </>
      )}
    </>
  )
}

/**
 * 注册 Monaco 快捷键（与右键菜单对应）。
 * Monaco 原生已有 Ctrl+V / Ctrl+F / Shift+Alt+F，这里补：
 * - Ctrl+Shift+C → 复制全部
 * - Ctrl+Shift+Delete → 清空
 */
function registerActions(
  editor: MonacoEditor,
  monaco: Monaco,
  value: string,
  onChange?: (value: string) => void,
  _language?: string,
) {
  // Ctrl+Shift+C → 复制全部
  editor.addAction({
    id: "copy-all",
    label: "复制全部",
    keybindings: [monaco.KeyMod.CtrlCmd | monaco.KeyMod.Shift | monaco.KeyCode.KeyC],
    run: async (ed) => {
      try {
        await navigator.clipboard.writeText(ed.getValue())
        toast.success("已复制全部内容")
      } catch {
        toast.error("复制失败，请用 Ctrl+C")
      }
    },
  })

  // Ctrl+Shift+Delete → 清空
  editor.addAction({
    id: "clear-editor",
    label: "清空编辑器",
    keybindings: [monaco.KeyMod.CtrlCmd | monaco.KeyMod.Shift | monaco.KeyCode.Delete],
    run: (ed) => {
      if (!window.confirm("确定清空编辑器内容？此操作不可撤销。")) return
      ed.setValue("")
      onChange?.("")
      ed.focus()
    },
  })
}
