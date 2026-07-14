import { describe, expect, it, vi, afterEach } from "vitest"
import { render, screen, fireEvent, cleanup } from "@testing-library/react"
import { NextIntlClientProvider } from "next-intl"

import { BriefingBanner, type FeedFilterValue } from "./briefing-banner"
import messages from "@/messages/zh-CN.json"
import type { IncidentStats } from "@/lib/supervision/types"

afterEach(cleanup)

function renderBanner(stats: IncidentStats, onFilter = vi.fn(), activeFilter: FeedFilterValue = { kind: "all" }) {
  render(
    <NextIntlClientProvider locale="zh-CN" messages={messages}>
      <BriefingBanner
        summaryLine="当前 3 起活跃事故"
        stats={stats}
        reportMd={null}
        connected
        activeFilter={activeFilter}
        onFilter={onFilter}
      />
    </NextIntlClientProvider>,
  )
  return onFilter
}

describe("BriefingBanner", () => {
  const stats: IncidentStats = { active: 3, agentWorking: 1, awaitingApproval: 1, needsHuman: 2, resolvedToday: 5 }

  it("渲染一句话综述与实时数字", () => {
    renderBanner(stats)
    expect(screen.getByText("当前 3 起活跃事故")).toBeTruthy()
    expect(screen.getByText("5")).toBeTruthy() // resolvedToday
    expect(screen.getByText("2")).toBeTruthy() // needsHuman
  })

  it("点击「需人工」数字回调 state=NEEDS_HUMAN 过滤", () => {
    const onFilter = renderBanner(stats)
    fireEvent.click(screen.getByTitle("需人工"))
    expect(onFilter).toHaveBeenCalledWith({ kind: "state", state: "NEEDS_HUMAN" })
  })

  it("再次点击已激活的过滤项 → 回到 all", () => {
    const onFilter = renderBanner(stats, vi.fn(), { kind: "state", state: "NEEDS_HUMAN" })
    fireEvent.click(screen.getByTitle("需人工"))
    expect(onFilter).toHaveBeenCalledWith({ kind: "all" })
  })
})
