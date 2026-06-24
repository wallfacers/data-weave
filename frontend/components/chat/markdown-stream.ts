/**
 * 流式 markdown 分块器 —— 移植自 workhorse-assistant（原 data-talk）。
 *
 * 把流式 markdown 的「尾部未闭合代码栅栏」隔离成独立的 stream-code 块，渲染器即可把
 * 该代码块当作稳定的纯 <pre>（append-only 文本 diff）增长，而非每 delta 重新解析——
 * 这是代码块抖动的主要来源。其前是一个 live 块；完成的消息是一个 full 块。
 */
import { marked, type Tokens } from "marked"

export type Block = {
  raw: string
  src: string
  mode: "full" | "live" | "stream-code"
  language?: string
  code?: string
  fence?: "```" | "~~~"
}

function refs(text: string): boolean {
  return /^\[[^\]]+\]:\s+\S+/m.test(text) || /^\[\^[^\]]+\]:\s+/m.test(text)
}

/** raw 是否开启了一个尚未闭合的栅栏。 */
function open(raw: string): boolean {
  const match = raw.match(/^[ \t]{0,3}(`{3,}|~{3,})/)
  if (!match) return false
  const mark = match[1]
  if (!mark) return false
  const char = mark[0]
  const size = mark.length
  const last = raw.trimEnd().split("\n").at(-1)?.trim() ?? ""
  return !new RegExp(`^[\\t ]{0,3}${char}{${size},}[\\t ]*$`).test(last)
}

/** 流式 delta 可能逐字符送达未完成的闭合栅栏（"`"、"``"）。剥掉只含 1..fenceLen-1 个
 *  栅栏字符的尾行，使可见行数稳定，直到真正的闭合栅栏到达。 */
function stripPartialClosingFence(code: string, fenceMarker: "```" | "~~~"): string {
  const lastNewlineIdx = code.lastIndexOf("\n")
  if (lastNewlineIdx < 0) return code
  const trailing = code.slice(lastNewlineIdx + 1)
  const fenceChar = fenceMarker[0]
  const fenceLen = fenceMarker.length
  const pattern = new RegExp(`^[ \\t ]{0,3}\\${fenceChar}{1,${fenceLen - 1}}$`)
  if (!pattern.test(trailing)) return code
  return code.slice(0, lastNewlineIdx + 1)
}

function parseOpenFence(raw: string): { language: string; code: string; fence: "```" | "~~~" } | null {
  const match = raw.match(/^[ \t]{0,3}(`{3,}|~{3,})([^\n]*)\n?([\s\S]*)$/)
  if (!match) return null
  const marker = match[1]!.startsWith("`") ? "```" : "~~~"
  const info = (match[2] ?? "").trim()
  const language = info.split(/\s+/)[0]?.toLowerCase() ?? ""
  const rawCode = match[3] ?? ""
  return { language, code: stripPartialClosingFence(rawCode, marker), fence: marker }
}

export function stream(text: string, live: boolean): Block[] {
  if (!live) return [{ raw: text, src: text, mode: "full" }]
  if (!text) return [{ raw: text, src: text, mode: "live" }]
  if (refs(text)) return [{ raw: text, src: text, mode: "live" }]

  const tokens = marked.lexer(text)
  let tail = -1
  for (let i = tokens.length - 1; i >= 0; i--) {
    if ((tokens[i] as { type: string }).type !== "space") {
      tail = i
      break
    }
  }
  if (tail < 0) return [{ raw: text, src: text, mode: "live" }]

  const last = tokens[tail]
  if (!last || last.type !== "code") return [{ raw: text, src: text, mode: "live" }]
  const code = last as Tokens.Code
  if (!open(code.raw)) return [{ raw: text, src: text, mode: "live" }]

  const openFence = parseOpenFence(code.raw)
  if (!openFence) return [{ raw: text, src: text, mode: "live" }]

  const head = tokens.slice(0, tail).map((t) => (t as { raw: string }).raw).join("")
  const streamCode: Block = {
    raw: code.raw,
    src: code.raw,
    mode: "stream-code",
    language: openFence.language,
    code: openFence.code,
    fence: openFence.fence,
  }
  if (!head) return [streamCode]
  return [{ raw: head, src: head, mode: "live" }, streamCode]
}
