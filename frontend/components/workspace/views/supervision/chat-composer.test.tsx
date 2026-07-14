import { describe, it, expect, vi, afterEach } from "vitest"
import { render, screen, fireEvent, cleanup, waitFor } from "@testing-library/react"
import { NextIntlClientProvider } from "next-intl"

import { ChatComposer } from "./chat-composer"

const messages = {
  supervision: {
    chatPlaceholder: "向运维 Agent 提问或下指令",
    chatDisabled: "智能运维未启用",
    chatSend: "发送",
    chatStop: "停止",
    chatSendFailed: "发送失败",
    cancelFailed: "打断失败，请重试",
  },
}

afterEach(cleanup)

function renderComposer(props: Partial<React.ComponentProps<typeof ChatComposer>> = {}) {
  const onSend = props.onSend ?? vi.fn().mockResolvedValue(undefined)
  const onCancel = props.onCancel ?? vi.fn().mockResolvedValue(undefined)
  render(
    <NextIntlClientProvider locale="zh-CN" messages={messages}>
      <ChatComposer onSend={onSend} onCancel={onCancel} streaming={props.streaming} disabled={props.disabled} />
    </NextIntlClientProvider>,
  )
  return { onSend, onCancel }
}

describe("ChatComposer 状态机", () => {
  it("空文本时发送键禁用", () => {
    renderComposer()
    expect(screen.getByRole("button", { name: "发送" })).toBeDisabled()
  })

  it("输入后可发送，成功清空输入", async () => {
    const { onSend } = renderComposer()
    const ta = screen.getByRole("textbox")
    fireEvent.change(ta, { target: { value: "  你好  " } })
    const btn = screen.getByRole("button", { name: "发送" })
    expect(btn).not.toBeDisabled()
    fireEvent.click(btn)
    expect(onSend).toHaveBeenCalledWith("你好") // trimmed
    await waitFor(() => expect((ta as HTMLTextAreaElement).value).toBe(""))
  })

  it("发送失败保留输入", async () => {
    const onSend = vi.fn().mockRejectedValue(new Error("boom"))
    renderComposer({ onSend })
    const ta = screen.getByRole("textbox")
    fireEvent.change(ta, { target: { value: "保留我" } })
    fireEvent.click(screen.getByRole("button", { name: "发送" }))
    await waitFor(() => expect(onSend).toHaveBeenCalled())
    expect((ta as HTMLTextAreaElement).value).toBe("保留我") // 未清空
  })

  it("streaming 时显示停止键，点击触发打断", async () => {
    const { onCancel } = renderComposer({ streaming: true })
    const stop = screen.getByRole("button", { name: "停止" })
    expect(stop).toBeTruthy()
    expect(screen.queryByRole("button", { name: "发送" })).toBeNull()
    fireEvent.click(stop)
    await waitFor(() => expect(onCancel).toHaveBeenCalled())
  })

  it("Enter 发送，Shift+Enter 不发送", () => {
    const { onSend } = renderComposer()
    const ta = screen.getByRole("textbox")
    fireEvent.change(ta, { target: { value: "行内" } })
    fireEvent.keyDown(ta, { key: "Enter", shiftKey: true })
    expect(onSend).not.toHaveBeenCalled()
    fireEvent.keyDown(ta, { key: "Enter" })
    expect(onSend).toHaveBeenCalledWith("行内")
  })
})
