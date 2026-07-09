import { describe, it, expect, vi, afterEach } from "vitest"
import { render, screen, fireEvent, cleanup } from "@testing-library/react"
import type { ReactNode } from "react"

// next-intl：返回 key 本身（选择器 label 即 i18n key，便于断言渲染了哪些类型）
vi.mock("next-intl", () => ({
  useTranslations: () => (key: string) => key,
}))

// 隔离 OverlayScrollbars（jsdom 无 ResizeObserver），只验证 option 渲染
vi.mock("@/components/ui/dw-scroll", () => ({
  DwScroll: ({ children }: { children: ReactNode }) => <div>{children}</div>,
}))

import { DropdownSelect } from "@/components/ui/select"
import { TASK_TYPES } from "@/components/workspace/shared/params-table"

// 面板 portal 到 document.body —— 显式清理，防跨用例残留污染
afterEach(cleanup)

/**
 * T024：创建任务类型选择器渲染全 8 类型。
 * DropdownSelect 是 catalog-tree / task-config-panel / workflow-canvas 三处创建入口
 * 共用的类型选择器组件；此处直接验证它能展开渲染 TASK_TYPES 全集。
 */
describe("创建任务类型选择器（T027/T028/T029 共用渲染）", () => {
  // 与三处创建入口一致的 8 类型 option 集合（label 用类型代号便于断言）
  const options = TASK_TYPES.map((tp) => ({ value: tp, label: tp }))

  it("展开后渲染全部 8 种任务类型供选择（SC-002）", () => {
    render(<DropdownSelect value="SQL" onChange={() => {}} options={options} disableClear />)

    // 折叠态：非选中类型不挂载
    expect(screen.queryByText("HIVE")).toBeNull()

    // 点击 trigger 展开（disableClear + 折叠态下唯一 button 即 trigger）
    fireEvent.click(screen.getByRole("button"))

    // 全 8 类型 option 均渲染（面板 portal 到 body）
    for (const tp of TASK_TYPES) {
      // SQL 在 trigger（选中态）与 option 各出现一次；其余仅在 option —— 至少出现一次即可
      const matches = screen.getAllByText(tp)
      expect(matches.length, `应渲染类型 ${tp}`).toBeGreaterThanOrEqual(1)
    }
  })

  it("初始展示当前选中类型", () => {
    render(<DropdownSelect value="SQL" onChange={() => {}} options={options} disableClear />)
    // trigger 显示选中值 SQL
    expect(screen.getAllByText("SQL").length).toBeGreaterThanOrEqual(1)
  })

  it("点击某类型后回调其 value", () => {
    let selected = ""
    render(
      <DropdownSelect
        value="SQL"
        onChange={(v) => {
          selected = v
        }}
        options={options}
        disableClear
      />,
    )
    fireEvent.click(screen.getByRole("button")) // 展开
    fireEvent.click(screen.getByText("FLINK")) // FLINK 非选中值，option 中唯一
    expect(selected).toBe("FLINK")
  })
})
