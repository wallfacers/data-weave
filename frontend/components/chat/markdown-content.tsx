/**
 * 流式安全 markdown 渲染器 —— 移植自 workhorse-assistant，适配 DataWeave。
 *
 * 为什么不用 react-markdown：它每个 streaming delta 都重新解析整段 markdown 并 reconcile
 * 整棵元素树，每帧重建代码块 DOM → 可见抖动。这里：
 *   - stream() 把尾部未闭合代码栅栏隔离为稳定块；
 *   - 已完成块按 hash 缓存，永不重解析；
 *   - morphdom 做最小 DOM patch（增长的代码是 append-only 文本 diff），已渲染内容不动；
 *   - 完成代码块由 Shiki 异步高亮后 patch 进去，再走缓存。
 */
"use client"

import { useEffect, useRef, useState } from "react"
import { marked } from "marked"
import morphdom from "morphdom"
import DOMPurify from "dompurify"
import { useTranslations } from "next-intl"
import { stream, type Block } from "./markdown-stream"
import { highlightCode } from "./highlighter"

// next-intl 无全局 t：copy 文案由组件 render 同步刷新此模块变量（同 locale 值一致）。
let COPY_LABEL = "Copy code"

const LANGUAGE_LABELS: Record<string, string> = {
  javascript: "JavaScript", js: "JavaScript", typescript: "TypeScript", ts: "TypeScript",
  jsx: "JSX", tsx: "TSX", python: "Python", py: "Python", java: "Java", kotlin: "Kotlin",
  c: "C", cpp: "C++", "c++": "C++", csharp: "C#", cs: "C#", go: "Go", golang: "Go",
  rust: "Rust", rs: "Rust", php: "PHP", ruby: "Ruby", rb: "Ruby", swift: "Swift",
  bash: "Bash", sh: "Shell", shell: "Shell", json: "JSON", html: "HTML", xml: "XML",
  css: "CSS", scss: "SCSS", sql: "SQL", yaml: "YAML", yml: "YAML", toml: "TOML",
  markdown: "Markdown", md: "Markdown", dockerfile: "Dockerfile", diff: "Diff",
}

marked.use({ gfm: true, breaks: false })

const COPY_SVG =
  '<svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect width="14" height="14" x="8" y="8" rx="2" ry="2"/><path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"/></svg>'
const CHECK_SVG =
  '<svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#22c55e" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6 9 17l-5-5"/></svg>'

const shikiCache = new Map<string, string>()
let instanceCounter = 0

function hash(text: string): string {
  let h = 0
  for (let i = 0; i < text.length; i++) h = ((h << 5) - h + text.charCodeAt(i)) | 0
  return h.toString(36)
}

function escapeHtml(text: string): string {
  return text
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;")
}

function langLabel(lang: string): string {
  const k = lang.toLowerCase()
  return LANGUAGE_LABELS[k] ?? (lang ? lang.replace(/^[a-z]/, (c) => c.toUpperCase()) : "")
}

const STREAMING_CODE_BODY_STYLE =
  "display:block;white-space:pre;word-break:normal;overflow-wrap:normal;min-height:1.5em;width:max-content;min-width:100%"

function renderStreamingCodeBlock(block: Block): string {
  const language = block.language ?? ""
  const languageClass = language ? `language-${escapeHtml(language)}` : ""
  const label = langLabel(language)
  const raw = block.code ?? ""
  const code = raw.endsWith("\n") ? raw : `${raw}\n`
  return [
    '<div data-component="markdown-code" data-streaming-code="true">',
    '<div data-slot="markdown-code-bar">',
    `<span data-slot="markdown-code-language">${escapeHtml(label)}</span>`,
    `<button data-slot="markdown-copy-button" type="button" aria-label="${escapeHtml(COPY_LABEL)}">${COPY_SVG}</button>`,
    "</div>",
    `<pre data-streaming-code="true"><code class="${languageClass}" style="${STREAMING_CODE_BODY_STYLE}">${escapeHtml(code)}</code></pre>`,
    "</div>",
  ].join("")
}

interface MarkdownContentProps {
  content: string
  streaming?: boolean
}

export default function MarkdownContent({ content, streaming = false }: MarkdownContentProps) {
  const t = useTranslations("chat")
  COPY_LABEL = t("copyCode")
  const ref = useRef<HTMLDivElement>(null)
  const instanceKey = useRef(`md-${++instanceCounter}`).current
  const blockCache = useRef<Map<string, { hash: string; html: string }>>(new Map())
  const [, setVersion] = useState(0)
  const aliveRef = useRef(true)
  useEffect(() => () => {
    aliveRef.current = false
  }, [])

  useEffect(() => {
    const container = ref.current
    if (!container) return

    const blocks = stream(content, streaming)
    const html = blocks
      .map((block, i) => {
        if (block.mode === "stream-code") return renderStreamingCodeBlock(block)
        const key = `${instanceKey}:${i}:${block.mode}`
        const h = hash(block.raw)
        const cached = blockCache.current.get(key)
        if (cached && cached.hash === h) return cached.html
        const parsed = marked.parse(block.src, { async: false }) as string
        const safe = DOMPurify.isSupported ? DOMPurify.sanitize(parsed) : parsed
        blockCache.current.set(key, { hash: h, html: safe })
        return safe
      })
      .join("")

    const temp = document.createElement("div")
    temp.innerHTML = html
    decorateCodeBlocks(temp)
    applyShiki(temp)
    wrapTables(temp)
    morphdom(container, temp, { childrenOnly: true })
  }, [content, streaming, instanceKey])

  function applyShiki(root: HTMLElement) {
    const blocksEls = Array.from(
      root.querySelectorAll("[data-component='markdown-code']"),
    ) as HTMLElement[]
    for (const el of blocksEls) {
      if (el.getAttribute("data-streaming-code") === "true") continue
      const code = el.querySelector("code")
      const text = code?.textContent?.replace(/\n$/, "") ?? ""
      if (!text) continue
      const lang = (code?.className.match(/language-([^\s]+)/)?.[1] ?? "").toLowerCase()
      const cacheKey = `${lang}\0${text}`
      const cachedHtml = shikiCache.get(cacheKey)
      const body = el.querySelector("pre")
      if (cachedHtml) {
        if (body && !el.querySelector(".shiki-wrapper")) {
          const wrap = document.createElement("div")
          wrap.className = "shiki-wrapper"
          wrap.innerHTML = cachedHtml
          body.replaceWith(wrap)
        }
        continue
      }
      void highlightCode(lang, text)
        .then((shikiHtml) => {
          shikiCache.set(cacheKey, shikiHtml)
          if (aliveRef.current) setVersion((v) => v + 1)
        })
        .catch(() => {
          /* 高亮失败：保留纯文本 */
        })
    }
  }

  return <div ref={ref} data-component="markdown" className="markdown-body" />
}

function decorateCodeBlocks(root: HTMLElement): void {
  const pres = Array.from(root.querySelectorAll("pre"))
  for (const pre of pres) {
    if (pre.parentElement?.getAttribute("data-component") === "markdown-code") continue
    if (pre.getAttribute("data-streaming-code") === "true") continue
    const code = pre.querySelector("code")
    const lang = (code?.className.match(/language-([^\s]+)/)?.[1] ?? "").toLowerCase()
    const wrapper = document.createElement("div")
    wrapper.setAttribute("data-component", "markdown-code")
    const bar = document.createElement("div")
    bar.setAttribute("data-slot", "markdown-code-bar")
    const label = document.createElement("span")
    label.setAttribute("data-slot", "markdown-code-language")
    label.textContent = langLabel(lang)
    const btn = document.createElement("button")
    btn.setAttribute("data-slot", "markdown-copy-button")
    btn.setAttribute("type", "button")
    btn.setAttribute("aria-label", COPY_LABEL)
    btn.innerHTML = COPY_SVG
    bar.append(label, btn)
    pre.parentNode?.replaceChild(wrapper, pre)
    wrapper.append(bar, pre)
  }
}

function wrapTables(root: HTMLElement): void {
  for (const table of Array.from(root.querySelectorAll("table"))) {
    if (table.parentElement?.getAttribute("data-slot") === "markdown-table-scroll") continue
    const frame = document.createElement("div")
    frame.setAttribute("data-slot", "markdown-table-scroll")
    frame.className = "my-2 overflow-hidden rounded-[var(--radius-sm)] border"
    table.parentNode?.replaceChild(frame, table)
    frame.appendChild(table)
  }
}

// 复制按钮：document 级委托监听
if (typeof document !== "undefined") {
  document.addEventListener("click", (e) => {
    const btn = (e.target as Element)?.closest?.(
      '[data-slot="markdown-copy-button"]',
    ) as HTMLElement | null
    if (!btn) return
    const block = btn.closest('[data-component="markdown-code"]')
    const text = block?.querySelector("code")?.textContent?.replace(/\n$/, "") ?? ""
    if (!text) return
    void navigator.clipboard?.writeText(text).then(() => {
      const original = btn.innerHTML
      btn.innerHTML = CHECK_SVG
      window.setTimeout(() => {
        btn.innerHTML = original
      }, 2000)
    })
  })
}
