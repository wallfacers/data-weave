/**
 * 视图级权限闸门判定单测（vitest，纯函数）。
 *
 * 回归：CTRL+R 刷新后 persistence 恢复上次激活的受限视图（如「系统设置」，
 * requirePermission=project:manage），而权限是异步加载（GET /me）。加载完成前
 * membership 为 null → can 恒 false，若直接判 denied 会闪现/误停在「权限不足」。
 * 本测试锁定：加载期（idle/loading）必须返回 loading，仅就绪后才判 allow/denied。
 */
import { describe, it, expect } from "vitest"

import { resolveViewGate } from "./view-gate"

const allow = () => true
const deny = () => false

describe("resolveViewGate", () => {
  it("无 requirePermission 的只读视图始终 allow", () => {
    expect(resolveViewGate(undefined, "idle", deny)).toBe("allow")
    expect(resolveViewGate(undefined, "loading", deny)).toBe("allow")
    expect(resolveViewGate(undefined, "ready", deny)).toBe("allow")
  })

  it("权限加载中（idle/loading）：受限视图渲染 loading，不误判权限不足（CTRL+R 刷新竞态）", () => {
    expect(resolveViewGate("project:manage", "idle", deny)).toBe("loading")
    expect(resolveViewGate("project:manage", "loading", deny)).toBe("loading")
    // 即便用户实际有权限，加载期也不应先渲染真实视图（避免闪烁 + 提前取数）
    expect(resolveViewGate("project:manage", "loading", allow)).toBe("loading")
  })

  it("权限就绪且持有：allow", () => {
    expect(resolveViewGate("project:manage", "ready", allow)).toBe("allow")
  })

  it("权限就绪但不持有：denied", () => {
    expect(resolveViewGate("project:manage", "ready", deny)).toBe("denied")
  })

  it("加载失败（error）：保守判 denied（无法核验权限）", () => {
    expect(resolveViewGate("project:manage", "error", deny)).toBe("denied")
  })
})
