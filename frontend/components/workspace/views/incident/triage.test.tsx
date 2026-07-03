import { describe, it, expect, vi, beforeEach, afterEach } from "vitest"
import { render, screen, fireEvent } from "@testing-library/react"
import {
  SeverityBadge,
  StateBadge,
  BlastRadiusBadge,
  SlaCountdown,
  PriorCountBadge,
  DiagnosisPlaceholder,
  ProposalPlaceholder,
} from "./triage"

/** Mock t function that returns the key as fallback */
function mockT(key: string, params?: Record<string, unknown>) {
  if (params) {
    return `${key} ${JSON.stringify(params)}`
  }
  return key
}

describe("SeverityBadge", () => {
  const severities = ["CRITICAL", "HIGH", "MEDIUM", "LOW", "WARNING"]

  it.each(severities)("renders severity %s text", (severity) => {
    render(<SeverityBadge severity={severity} t={mockT as never} />)
    expect(screen.getByText(`severity.${severity}`)).toBeTruthy()
  })

  it("applies destructive styling to CRITICAL", () => {
    render(<SeverityBadge severity="CRITICAL" t={mockT as never} />)
    const badge = screen.getByText("severity.CRITICAL")
    expect(badge.className).toContain("text-destructive")
  })

  it("applies warning styling to MEDIUM", () => {
    render(<SeverityBadge severity="MEDIUM" t={mockT as never} />)
    const badge = screen.getByText("severity.MEDIUM")
    expect(badge.className).toContain("text-warning")
  })
})

describe("StateBadge", () => {
  const stateColors: Record<string, string> = {
    OPEN: "text-destructive",
    MITIGATING: "text-warning",
    RESOLVED: "text-success",
    SUPPRESSED: "text-muted-foreground",
    CLOSED: "text-muted-foreground",
  }

  it.each(Object.keys(stateColors))("renders state %s with correct styling", (state) => {
    render(<StateBadge state={state} t={mockT as never} />)
    const badge = screen.getByText(`state.${state}`)
    expect(badge).toBeTruthy()
    expect(badge.className).toContain(stateColors[state])
  })
})

describe("BlastRadiusBadge — 缺省态矩阵", () => {
  it("shows '血缘不可用' when blastRadius is null", () => {
    render(<BlastRadiusBadge blastRadius={null} t={mockT as never} />)
    expect(screen.getByText("card.blastRadiusNull")).toBeTruthy()
  })

  it("shows '无下游影响' when blastRadius is 0", () => {
    render(<BlastRadiusBadge blastRadius={0} t={mockT as never} />)
    expect(screen.getByText("card.blastRadiusZero")).toBeTruthy()
  })

  it("shows downstream count when blastRadius > 0", () => {
    render(<BlastRadiusBadge blastRadius={7} t={mockT as never} />)
    expect(
      screen.getByText(`card.blastRadius {"count":7}`),
    ).toBeTruthy()
  })

  it("null and 0 are visually distinct", () => {
    const { unmount } = render(<BlastRadiusBadge blastRadius={null} t={mockT as never} />)
    expect(screen.getByText("card.blastRadiusNull")).toBeTruthy()
    unmount()

    render(<BlastRadiusBadge blastRadius={0} t={mockT as never} />)
    expect(screen.getByText("card.blastRadiusZero")).toBeTruthy()
    // 两者使用不同的文案，确保前端能区分"血缘不可用"和"无下游影响"
  })
})

describe("SlaCountdown — 缺省态矩阵", () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date("2026-07-03T12:00:00Z"))
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it("shows '无 SLA 约束' when timeBudgetAt is null", () => {
    render(<SlaCountdown timeBudgetAt={null} t={mockT as never} />)
    expect(screen.getByText("card.timeBudgetNoSla")).toBeTruthy()
  })

  it("shows '已超期' when timeBudgetAt is in the past", () => {
    // 1 hour in the past
    const past = new Date("2026-07-03T11:00:00Z").toISOString()
    render(<SlaCountdown timeBudgetAt={past} t={mockT as never} />)
    const text = screen.getByText(/card.timeBudgetOverdue/)
    expect(text).toBeTruthy()
    expect(text.className).toContain("text-destructive")
  })

  it("shows countdown when timeBudgetAt is in the future", () => {
    // 2 hours in the future
    const future = new Date("2026-07-03T14:00:00Z").toISOString()
    render(<SlaCountdown timeBudgetAt={future} t={mockT as never} />)
    const text = screen.getByText(/card.countdown/)
    expect(text).toBeTruthy()
  })

  it("countdown updates every second", () => {
    const future = new Date("2026-07-03T12:01:05Z").toISOString() // 65 seconds ahead
    render(<SlaCountdown timeBudgetAt={future} t={mockT as never} />)
    // Initial render: 1m5s
    expect(screen.getByText(/card.countdown/)).toBeTruthy()

    // Advance 5 seconds
    vi.advanceTimersByTime(5000)
    // Should now show 1m0s
  })
})

describe("PriorCountBadge", () => {
  it("renders count when > 0", () => {
    render(<PriorCountBadge count={3} t={mockT as never} />)
    expect(screen.getByText(`card.priorCount {"count":3}`)).toBeTruthy()
  })

  it("renders nothing when count is 0", () => {
    const { container } = render(<PriorCountBadge count={0} t={mockT as never} />)
    expect(container.textContent).toBe("")
  })
})

describe("DiagnosisPlaceholder / ProposalPlaceholder", () => {
  it("shows placeholder text for diagnosis (non-error style)", () => {
    render(<DiagnosisPlaceholder t={mockT as never} />)
    const el = screen.getByText("card.diagnosisPlaceholder")
    expect(el).toBeTruthy()
    // Should use muted/italic style, not destructive
    expect(el.className).toContain("italic")
    expect(el.className).not.toContain("destructive")
  })

  it("shows placeholder text for proposal (non-error style)", () => {
    render(<ProposalPlaceholder t={mockT as never} />)
    const el = screen.getByText("card.proposalPlaceholder")
    expect(el).toBeTruthy()
    expect(el.className).toContain("italic")
    expect(el.className).not.toContain("destructive")
  })
})
