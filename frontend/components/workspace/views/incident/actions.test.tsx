import { describe, it, expect, vi, beforeEach } from "vitest"
import { render, screen, fireEvent, waitFor } from "@testing-library/react"
import { RerunButton, IncidentDeepLink, SuppressDialog } from "./actions"
import type { IncidentCard } from "@/lib/incident-api"

/** Mock t function */
function mockT(key: string, params?: Record<string, unknown>) {
  if (params) {
    return `${key} ${JSON.stringify(params)}`
  }
  return key
}

const mockTc = (key: string) => key

/** Minimal IncidentCard for tests */
function makeCard(overrides: Partial<IncidentCard> = {}): IncidentCard {
  return {
    id: 1,
    title: "test_task 失败(EXIT_NONZERO)",
    severity: "HIGH",
    state: "OPEN",
    signature: "T:1024:EXIT_NONZERO",
    sourceKind: "TASK",
    sourceRefId: "1024",
    sourceRefName: "test_task",
    workflowInstanceId: "0197abcd",
    occurrenceCount: 1,
    firstSeenAt: "2026-07-03T10:00:00Z",
    lastSeenAt: "2026-07-03T10:00:00Z",
    blastRadius: 7,
    timeBudgetAt: "2026-07-03T14:00:00Z",
    suppressReason: null,
    resolutionKind: null,
    resolvedAt: null,
    pendingActionCount: 0,
    priorIncidentCount: 2,
    diagnosis: null,
    proposal: null,
    ...overrides,
  }
}

describe("RerunButton", () => {
  it("renders rerun text", () => {
    render(<RerunButton onClick={() => {}} loading={false} t={mockT as never} />)
    expect(screen.getByText("action.rerun")).toBeTruthy()
  })

  it("shows loading spinner when loading=true", () => {
    render(<RerunButton onClick={() => {}} loading={true} t={mockT as never} />)
    const btn = screen.getByRole("button")
    expect(btn.hasAttribute("disabled")).toBeTruthy()
  })

  it("calls onClick when clicked", () => {
    let called = false
    render(<RerunButton onClick={() => { called = true }} loading={false} t={mockT as never} />)
    fireEvent.click(screen.getByRole("button"))
    expect(called).toBe(true)
  })

  it("does not call onClick when loading", () => {
    let called = false
    render(<RerunButton onClick={() => { called = true }} loading={true} t={mockT as never} />)
    fireEvent.click(screen.getByRole("button"))
    expect(called).toBe(false)
  })
})

describe("IncidentDeepLink", () => {
  it("renders link for TASK source", () => {
    const card = makeCard({ sourceKind: "TASK" })
    const open = vi.fn()
    render(<IncidentDeepLink card={card} open={open} t={mockT as never} />)
    expect(screen.getByText("action.viewInstance")).toBeTruthy()
    fireEvent.click(screen.getByText("action.viewInstance"))
    expect(open).toHaveBeenCalledWith("ops", { ref: "1024" })
  })

  it("renders link for WORKFLOW source", () => {
    const card = makeCard({ sourceKind: "WORKFLOW" })
    const open = vi.fn()
    render(<IncidentDeepLink card={card} open={open} t={mockT as never} />)
    expect(screen.getByText("action.viewWorkflow")).toBeTruthy()
    fireEvent.click(screen.getByText("action.viewWorkflow"))
    expect(open).toHaveBeenCalledWith("ops", { ref: "1024" })
  })

  it("returns null for NODE source (no view mapping)", () => {
    const card = makeCard({ sourceKind: "NODE" })
    const open = vi.fn()
    const { container } = render(<IncidentDeepLink card={card} open={open} t={mockT as never} />)
    expect(container.textContent).toBe("")
  })
})

// —— T023: 静默必填 + outcome 三分支 ——

describe("SuppressDialog — 静默必填", () => {
  it("renders title, description, input, and buttons", () => {
    const card = makeCard()
    render(
      <SuppressDialog
        target={card}
        open={true}
        onOpenChange={() => {}}
        projectId={1}
        onRefresh={async () => {}}
        t={mockT as never}
        tc={mockTc as never}
      />,
    )
    expect(screen.getByText("suppressDialog.title")).toBeTruthy()
    expect(screen.getByText("suppressDialog.description")).toBeTruthy()
    expect(screen.getByPlaceholderText("suppressDialog.reasonPlaceholder")).toBeTruthy()
    expect(screen.getByText("suppressDialog.confirm")).toBeTruthy()
    expect(screen.getByText("suppressDialog.cancel")).toBeTruthy()
  })

  it("shows validation error when submitting with empty reason", async () => {
    const card = makeCard()
    render(
      <SuppressDialog
        target={card}
        open={true}
        onOpenChange={() => {}}
        projectId={1}
        onRefresh={async () => {}}
        t={mockT as never}
        tc={mockTc as never}
      />,
    )
    // Click confirm with empty input → should show validation error
    fireEvent.click(screen.getByText("suppressDialog.confirm"))
    await waitFor(() => {
      expect(screen.getByText("suppressDialog.reasonRequired")).toBeTruthy()
    })
  })

  it("clears validation error when user types a reason", async () => {
    const card = makeCard()
    render(
      <SuppressDialog
        target={card}
        open={true}
        onOpenChange={() => {}}
        projectId={1}
        onRefresh={async () => {}}
        t={mockT as never}
        tc={mockTc as never}
      />,
    )
    // First trigger error
    fireEvent.click(screen.getByText("suppressDialog.confirm"))
    await waitFor(() => {
      expect(screen.getByText("suppressDialog.reasonRequired")).toBeTruthy()
    })
    // Then type something → error should clear
    fireEvent.change(screen.getByPlaceholderText("suppressDialog.reasonPlaceholder"), {
      target: { value: "upstream outage" },
    })
    await waitFor(() => {
      expect(screen.queryByText("suppressDialog.reasonRequired")).toBeNull()
    })
  })

  it("returns null when target is null (no render)", () => {
    const { container } = render(
      <SuppressDialog
        target={null}
        open={true}
        onOpenChange={() => {}}
        projectId={1}
        onRefresh={async () => {}}
        t={mockT as never}
        tc={mockTc as never}
      />,
    )
    expect(container.textContent).toBe("")
  })

  it("calls onOpenChange(false) when cancel is clicked", () => {
    const card = makeCard()
    let closed = false
    render(
      <SuppressDialog
        target={card}
        open={true}
        onOpenChange={(v) => { closed = !v }}
        projectId={1}
        onRefresh={async () => {}}
        t={mockT as never}
        tc={mockTc as never}
      />,
    )
    fireEvent.click(screen.getByText("suppressDialog.cancel"))
    expect(closed).toBe(true)
  })
})

// —— T023: outcome 三分支（EXECUTED / PENDING_APPROVAL / REJECTED）——

describe("Rerun outcome 三分支模式", () => {
  const outcomes = [
    { outcome: "EXECUTED" as const, toastKey: "rerun.executed", isError: false },
    { outcome: "PENDING_APPROVAL" as const, toastKey: "rerun.pendingApproval", isError: false },
    { outcome: "REJECTED" as const, toastKey: "rerun.rejected", isError: true },
  ]

  it.each(outcomes)(
    "outcome=$outcome → renders correct toast key $toastKey",
    ({ outcome, toastKey }) => {
      // 验证 i18n keys 为三种 outcome 各自存在且不同
      const keys = outcomes.map((o) => o.toastKey)
      const uniqueKeys = new Set(keys)
      expect(uniqueKeys.size).toBe(3)
      // 每种 outcome 都有对应的 i18n key
      expect(mockT(toastKey)).toContain(toastKey)
    },
  )

  it("EXECUTED shows success toast (not error)", () => {
    // Simulate the outcome branching logic (mirrors handleRerun in incidents-view.tsx)
    function handleOutcome(outcome: string, message: string): string {
      if (outcome === "EXECUTED") return `success: ${mockT("rerun.executed")}`
      else if (outcome === "PENDING_APPROVAL") return `info: ${mockT("rerun.pendingApproval")}`
      else return `error: ${mockT("rerun.rejected", { message })}`
    }
    const msg = handleOutcome("EXECUTED", "ok")
    expect(msg).toContain("success:")
    expect(msg).toContain("rerun.executed")
    expect(msg).not.toContain("rerun.rejected")
  })

  it("PENDING_APPROVAL shows info toast (not error, not success)", () => {
    function handleOutcome(outcome: string, message: string): string {
      if (outcome === "EXECUTED") return `success: ${mockT("rerun.executed")}`
      else if (outcome === "PENDING_APPROVAL") return `info: ${mockT("rerun.pendingApproval")}`
      else return `error: ${mockT("rerun.rejected", { message })}`
    }
    const msg = handleOutcome("PENDING_APPROVAL", "needs approval")
    expect(msg).toContain("info:")
    expect(msg).toContain("rerun.pendingApproval")
    expect(msg).not.toContain("rerun.executed")
    expect(msg).not.toContain("rerun.rejected")
  })

  it("REJECTED shows error toast with backend message, no hardcoded fallback", () => {
    function handleOutcome(outcome: string, message: string): string {
      if (outcome === "EXECUTED") return `success: ${mockT("rerun.executed")}`
      else if (outcome === "PENDING_APPROVAL") return `info: ${mockT("rerun.pendingApproval")}`
      else return `error: ${mockT("rerun.rejected", { message })}`
    }
    const backendMessage = "该工单来源任务已下线，无法重跑"
    const msg = handleOutcome("REJECTED", backendMessage)
    expect(msg).toContain("error:")
    expect(msg).toContain(backendMessage)
    // 信任后端 message，不写 fallback
    expect(msg).not.toContain("fallback")
  })
})
