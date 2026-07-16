"use client"

/**
 * 070 US1 对话富文本渲染（监督席 Agent 回复 / 接班报告）。
 *
 * 设计取舍：用 react-markdown + remark-gfm，代码块桥接既有 {@link CodeBlock}（复用项目
 * `dataweave-light/dark` Shiki 双主题，主题切换零重高亮），而非引入自带 mermaid/katex/第二套
 * Shiki 的重型流式库——契合 DESIGN.md「复用既有高亮体系」。流式安全：{@link completePartialMarkdown}
 * 闭合未完成的代码围栏，避免 delta 途中版式塌陷。单条渲染失败由 {@link MarkdownBoundary} 隔离降级。
 */
import * as React from "react"
import ReactMarkdown, { type Components } from "react-markdown"
import remarkGfm from "remark-gfm"
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import { Copy01Icon, Tick02Icon } from "@hugeicons/core-free-icons"

import { CodeBlock } from "./code-block"
import { cn } from "@/lib/utils"

/**
 * 流式安全：闭合未完成的三反引号代码围栏（奇数个 ``` → 补一个），
 * 使 delta 逐字到达时不把后续正文吞进一个巨大的未闭合代码块。
 */
export function completePartialMarkdown(text: string): string {
  const fences = (text.match(/```/g) ?? []).length
  return fences % 2 === 1 ? `${text}\n\`\`\`` : text
}

/** 代码块 chrome：语言标签 + 复制按钮（2s 对勾确认，幂等），主体走既有 Shiki CodeBlock。 */
function CodeBlockChrome({ code, lang }: { code: string; lang: string }) {
  const t = useTranslations("chat")
  const [copied, setCopied] = React.useState(false)
  const timerRef = React.useRef<ReturnType<typeof setTimeout> | null>(null)

  React.useEffect(() => () => {
    if (timerRef.current) clearTimeout(timerRef.current)
  }, [])

  const copy = React.useCallback(() => {
    void navigator.clipboard?.writeText(code).then(() => {
      setCopied(true)
      if (timerRef.current) clearTimeout(timerRef.current)
      timerRef.current = setTimeout(() => setCopied(false), 2000)
    })
  }, [code])

  return (
    <div className="my-1.5 overflow-hidden rounded-[var(--radius)] border border-border bg-muted/30">
      <div className="flex items-center justify-between gap-2 px-2.5 py-1 text-[11px] text-muted-foreground">
        <span className="font-mono">{lang}</span>
        <button
          type="button"
          onClick={copy}
          className="inline-flex items-center gap-1 rounded-xs px-1 py-0.5 transition-colors hover:text-foreground"
          aria-label={copied ? t("copied") : t("copyCode")}
        >
          <HugeiconsIcon icon={copied ? Tick02Icon : Copy01Icon} className="size-3" />
          {copied ? t("copied") : t("copyCode")}
        </button>
      </div>
      <CodeBlock code={code} lang={lang} />
    </div>
  )
}

const MARKDOWN_COMPONENTS: Components = {
  // 代码块：由 pre 读取内层 code 元素统一渲染（block 走 Shiki chrome），inline 走 code 分支。
  pre({ children }) {
    const el = React.Children.toArray(children)[0] as React.ReactElement<{
      className?: string
      children?: React.ReactNode
    }> | undefined
    const cls = el?.props?.className ?? ""
    const match = /language-(\w+)/.exec(cls)
    const raw = String(el?.props?.children ?? "").replace(/\n$/, "")
    return <CodeBlockChrome code={raw} lang={match?.[1] ?? "text"} />
  },
  code({ children }) {
    return (
      <code className="rounded bg-muted px-1 py-0.5 font-mono text-[0.85em] text-link">
        {children}
      </code>
    )
  },
  p({ children }) {
    return <p className="my-1 leading-relaxed first:mt-0 last:mb-0">{children}</p>
  },
  ul({ children }) {
    return <ul className="my-1 ml-4 list-disc space-y-0.5 marker:text-muted-foreground/60">{children}</ul>
  },
  ol({ children }) {
    return <ol className="my-1 ml-4 list-decimal space-y-0.5 marker:text-muted-foreground/60">{children}</ol>
  },
  li({ children }) {
    return <li className="leading-relaxed">{children}</li>
  },
  h1({ children }) {
    return <p className="mt-2 mb-1 text-sm font-semibold text-foreground first:mt-0">{children}</p>
  },
  h2({ children }) {
    return <p className="mt-2 mb-1 text-sm font-semibold text-foreground first:mt-0">{children}</p>
  },
  h3({ children }) {
    return <p className="mt-2 mb-1 text-sm font-semibold text-foreground first:mt-0">{children}</p>
  },
  strong({ children }) {
    return <strong className="font-semibold text-foreground">{children}</strong>
  },
  em({ children }) {
    return <em className="italic">{children}</em>
  },
  a({ children, href }) {
    return (
      <a href={href} target="_blank" rel="noreferrer" className="text-link underline underline-offset-2 hover:opacity-80">
        {children}
      </a>
    )
  },
  blockquote({ children }) {
    return <blockquote className="my-1 border-l-2 border-border pl-2.5 text-muted-foreground">{children}</blockquote>
  },
  hr() {
    return <hr className="my-2 border-border" />
  },
  table({ children }) {
    return (
      <div className="my-1.5 overflow-x-auto">
        <table className="w-full border-collapse text-xs">{children}</table>
      </div>
    )
  },
  th({ children }) {
    return <th className="border border-border bg-muted/40 px-2 py-1 text-left font-medium">{children}</th>
  },
  td({ children }) {
    return <td className="border border-border px-2 py-1">{children}</td>
  },
}

/** 单条消息渲染错误隔离：崩溃则降级为纯文本原文，不拖垮整个线程。 */
class MarkdownBoundary extends React.Component<
  { fallback: string; children: React.ReactNode },
  { failed: boolean }
> {
  constructor(props: { fallback: string; children: React.ReactNode }) {
    super(props)
    this.state = { failed: false }
  }
  static getDerivedStateFromError(): { failed: boolean } {
    return { failed: true }
  }
  render() {
    if (this.state.failed) {
      return <p className="whitespace-pre-wrap break-words text-sm text-foreground">{this.props.fallback}</p>
    }
    return this.props.children
  }
}

/**
 * 对话富文本。`streaming` 时对未闭合代码围栏做安全闭合。
 * 渲染崩溃降级为纯文本（fallback=原始 content）。
 */
export function ChatMarkdown({
  content,
  streaming = false,
  className,
}: {
  content: string
  streaming?: boolean
  className?: string
}) {
  const source = streaming ? completePartialMarkdown(content) : content
  return (
    <div className={cn("text-sm text-foreground [&>*:first-child]:mt-0 [&>*:last-child]:mb-0", className)}>
      <MarkdownBoundary fallback={content}>
        <ReactMarkdown remarkPlugins={[remarkGfm]} components={MARKDOWN_COMPONENTS}>
          {source}
        </ReactMarkdown>
      </MarkdownBoundary>
    </div>
  )
}
