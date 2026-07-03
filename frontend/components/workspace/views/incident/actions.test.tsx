import { describe, it, expect, vi } from "vitest"
import { render, screen, fireEvent, waitFor } from "@testing-library/react"
import {
  RerunButton,
  IncidentDeepLink,
  type IncidentCard,
} from "./actions"

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
    // Button should be disabled during loading
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
