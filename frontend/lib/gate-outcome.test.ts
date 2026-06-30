import { describe, it, expect } from "vitest"
import { resolveGate, gateToast } from "./gate-outcome"
import type { ApiResponse, GateResult } from "./catalog-api"

function res(partial: Partial<ApiResponse<GateResult>>): ApiResponse<GateResult> {
  return { code: 0, data: { outcome: "EXECUTED" }, ...partial } as ApiResponse<GateResult>
}

describe("resolveGate（三态如实）", () => {
  it("code=0 + EXECUTED → executed", () => {
    expect(resolveGate(res({ code: 0, data: { outcome: "EXECUTED" } }))).toEqual({ kind: "executed" })
  })

  it("PENDING_APPROVAL → pending（不伪装成功）", () => {
    expect(resolveGate(res({ code: 0, data: { outcome: "PENDING_APPROVAL" } }))).toEqual({ kind: "pending" })
  })

  it("REJECTED → failed", () => {
    const r = resolveGate(res({ code: 0, data: { outcome: "REJECTED" }, message: "no" }))
    expect(r.kind).toBe("failed")
  })

  it("code≠0 + errorCode → failed 携带 errorCode + message", () => {
    const r = resolveGate(res({ code: 409, errorCode: "catalog.duplicate_asset", message: "dup", data: { outcome: "REJECTED" } }))
    expect(r).toEqual({ kind: "failed", errorCode: "catalog.duplicate_asset", backendMessage: "dup" })
  })

  it("null/undefined → failed", () => {
    expect(resolveGate(null).kind).toBe("failed")
    expect(resolveGate(undefined).kind).toBe("failed")
  })
})

describe("gateToast", () => {
  const t = (k: string) => `T:${k}`

  it("executed → actionDone", () => {
    expect(gateToast({ kind: "executed" }, t)).toBe("T:actionDone")
  })

  it("pending → pendingApproval", () => {
    expect(gateToast({ kind: "pending" }, t)).toBe("T:pendingApproval")
  })

  it("failed + 已知 errorCode → 映射的 key", () => {
    const msg = gateToast(
      { kind: "failed", errorCode: "catalog.reuse_cycle", backendMessage: "x" },
      t,
      { "catalog.reuse_cycle": "errReuseCycle" },
    )
    expect(msg).toBe("T:errReuseCycle")
  })

  it("failed + 未知 errorCode → 回落后端 message", () => {
    expect(gateToast({ kind: "failed", errorCode: "catalog.unknown", backendMessage: "boom" }, t)).toBe("boom")
  })

  it("failed + 无 message → 通用 actionFailed", () => {
    expect(gateToast({ kind: "failed" }, t)).toBe("T:actionFailed")
    expect(gateToast({ kind: "failed", backendMessage: "  " }, t)).toBe("T:actionFailed")
  })
})
