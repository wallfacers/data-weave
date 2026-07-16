import { describe, it, expect } from "vitest"
import { render, screen } from "@testing-library/react"
import { NextIntlClientProvider } from "next-intl"

import { ChatMarkdown, completePartialMarkdown } from "./chat-markdown"

const messages = { chat: { copyCode: "Copy", copied: "Copied" } }

function renderMd(content: string, streaming = false) {
  return render(
    <NextIntlClientProvider locale="en-US" messages={messages}>
      <ChatMarkdown content={content} streaming={streaming} />
    </NextIntlClientProvider>,
  )
}

describe("completePartialMarkdown", () => {
  it("闭合奇数个代码围栏", () => {
    expect(completePartialMarkdown("```sql\nSELECT 1")).toBe("```sql\nSELECT 1\n```")
  })
  it("偶数（已闭合）围栏原样返回", () => {
    const closed = "```sql\nSELECT 1\n```"
    expect(completePartialMarkdown(closed)).toBe(closed)
  })
  it("无围栏原样返回", () => {
    expect(completePartialMarkdown("普通文本 **加粗**")).toBe("普通文本 **加粗**")
  })
})

describe("ChatMarkdown 渲染", () => {
  it("加粗渲染为 <strong>", () => {
    renderMd("这是 **重点** 内容")
    const el = screen.getByText("重点")
    expect(el.tagName).toBe("STRONG")
  })

  it("无序列表渲染为 <li>", () => {
    renderMd("- 第一项\n- 第二项")
    expect(screen.getByText("第一项").closest("li")).not.toBeNull()
    expect(screen.getByText("第二项").closest("li")).not.toBeNull()
  })

  it("行内代码渲染为 <code>", () => {
    renderMd("调用 `SELECT 1` 查询")
    const el = screen.getByText("SELECT 1")
    expect(el.tagName).toBe("CODE")
  })

  it("流式未闭合围栏不抛错并渲染出代码块头（复制按钮）", () => {
    renderMd("```sql\nSELECT * FROM t", true)
    // completePartialMarkdown 闭合围栏 → 走代码块 chrome，复制按钮出现
    expect(screen.getByRole("button", { name: "Copy" })).toBeTruthy()
  })

  it("未闭合行内加粗不抛错", () => {
    expect(() => renderMd("未闭合 **加粗", true)).not.toThrow()
  })
})
