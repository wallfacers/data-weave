import { describe, it, expect, beforeEach, vi } from "vitest"
import { render, screen, fireEvent, waitFor, within } from "@testing-library/react"
import { NextIntlClientProvider } from "next-intl"

import { ProblemList } from "./problem-list"
import { useCompanionStore } from "@/lib/companion/store"
import { fetchReports, closeReport } from "@/lib/companion/api"
import type { ReportView } from "@/lib/companion/types"

/* DwScroll 依赖 OverlayScrollbars，jsdom 下透传为普通 div，聚焦业务逻辑 */
vi.mock("@/components/ui/dw-scroll", () => ({
  DwScroll: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}))

/* 网络层：fetchReports 提供种子、closeReport 即时 resolve（无后端） */
vi.mock("@/lib/companion/api", () => ({
  fetchReports: vi.fn().mockResolvedValue([]),
  closeReport: vi.fn().mockResolvedValue(undefined),
}))

const messages = {
  companion: {
    report: {
      title: "Reports",
      empty: "No reports",
      close: "Close",
      viewDetail: "View Details",
      domain: {
        TASK_FAILURE: "Task Failure Patrol",
        MACHINE_STATUS: "Machine Status Patrol",
        DATA_QUALITY: "Data Quality Patrol",
        CODE_QUALITY: "Code Quality Patrol",
      },
    },
    chat: { sendFailed: "Send failed" },
  },
}

function renderList() {
  return render(
    <NextIntlClientProvider locale="en-US" messages={messages}>
      <ProblemList />
    </NextIntlClientProvider>,
  )
}

function makeReport(overrides: Partial<ReportView> = {}): ReportView {
  return {
    id: "r1",
    domain: "TASK_FAILURE",
    severity: "WARN",
    title: "Task failed",
    summary: "",
    aggregateCount: 1,
    status: "UNREAD",
    createdAt: "2026-07-15T12:00:00Z",
    ...overrides,
  }
}

/** 折叠按钮（标题 Reports + 徽标） */
function headerButton() {
  return screen.getByRole("button", { name: /Reports/ })
}

describe("ProblemList", () => {
  beforeEach(() => {
    useCompanionStore.setState({ reports: [] })
    vi.mocked(fetchReports).mockResolvedValue([])
    vi.mocked(closeReport).mockResolvedValue(undefined)
  })

  /* 行数 = 汇报数 */
  it("行数等于汇报数", async () => {
    vi.mocked(fetchReports).mockResolvedValue([
      makeReport({ id: "r1", title: "A" }),
      makeReport({ id: "r2", title: "B" }),
      makeReport({ id: "r3", title: "C" }),
    ])
    renderList()

    await waitFor(() => {
      expect(screen.getAllByLabelText("Close")).toHaveLength(3)
    })
    expect(screen.getAllByLabelText("View Details")).toHaveLength(3)
  })

  /* 折叠切换：点收起 → 行消失；再点 → 行再现 */
  it("折叠切换列表显隐", async () => {
    vi.mocked(fetchReports).mockResolvedValue([makeReport({ id: "r1", title: "A" })])
    renderList()

    await waitFor(() => expect(screen.getAllByLabelText("Close")).toHaveLength(1))

    fireEvent.click(headerButton())
    expect(screen.queryByLabelText("Close")).toBeNull()
    expect(screen.queryByLabelText("View Details")).toBeNull()

    fireEvent.click(headerButton())
    await waitFor(() => expect(screen.getAllByLabelText("Close")).toHaveLength(1))
  })

  /* 关闭移除行 + 未读计数减一 */
  it("关闭一行后行移除且未读徽标减一", async () => {
    vi.mocked(fetchReports).mockResolvedValue([
      makeReport({ id: "r1", title: "A", status: "UNREAD" }),
      makeReport({ id: "r2", title: "B", status: "UNREAD" }),
    ])
    renderList()

    await waitFor(() => expect(screen.getAllByLabelText("Close")).toHaveLength(2))
    expect(within(headerButton()).getByText("2")).toBeTruthy()

    fireEvent.click(screen.getAllByLabelText("Close")[0])

    await waitFor(() => expect(screen.getAllByLabelText("Close")).toHaveLength(1))
    expect(within(headerButton()).getByText("1")).toBeTruthy()
    expect(closeReport).toHaveBeenCalledTimes(1)
  })

  /* 空态 */
  it("无汇报时显示空态文案", async () => {
    renderList()
    await waitFor(() => expect(screen.getByText("No reports")).toBeTruthy())
    expect(screen.queryByLabelText("Close")).toBeNull()
  })
})
