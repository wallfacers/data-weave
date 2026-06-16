import { describe, expect, it } from "vitest"

import {
  clipboardCaps,
  isFormattable,
  toolbarActions,
} from "./code-editor-actions"

describe("isFormattable", () => {
  it("json/ts/js 有内建 formatter", () => {
    expect(isFormattable("json")).toBe(true)
    expect(isFormattable("typescript")).toBe(true)
    expect(isFormattable("javascript")).toBe(true)
  })

  it("sql/bash/python 无 formatter → 格式化置灰", () => {
    expect(isFormattable("sql")).toBe(false)
    expect(isFormattable("bash")).toBe(false)
    expect(isFormattable("python")).toBe(false)
  })
})

describe("clipboardCaps —— 能力探测", () => {
  it("navigator 缺失视作不可用（SSR 安全）", () => {
    expect(clipboardCaps(undefined)).toEqual({ canWrite: false, canRead: false })
  })

  it("无 clipboard 时读写皆不可用 → 按钮置灰", () => {
    expect(clipboardCaps({})).toEqual({ canWrite: false, canRead: false })
  })

  it("仅有 writeText 时只可复制（读权限缺失）", () => {
    const caps = clipboardCaps({ clipboard: { writeText: async () => {} } as unknown as Clipboard })
    expect(caps).toEqual({ canWrite: true, canRead: false })
  })

  it("读写齐备时两者皆可用", () => {
    const caps = clipboardCaps({
      clipboard: {
        writeText: async () => {},
        readText: async () => "",
      } as unknown as Clipboard,
    })
    expect(caps).toEqual({ canWrite: true, canRead: true })
  })
})

describe("toolbarActions —— readOnly 收敛", () => {
  it("可写时五组操作齐全", () => {
    expect(toolbarActions(false)).toEqual([
      "copy",
      "paste",
      "format",
      "clear",
      "find",
    ])
  })

  it("只读时仅留复制 / 查找，隐藏写操作", () => {
    expect(toolbarActions(true)).toEqual(["copy", "find"])
  })
})
