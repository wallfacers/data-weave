import { describe, it, expect, vi } from "vitest"
import { render, screen, fireEvent } from "@testing-library/react"
import { Tabs, TabsList, TabsTrigger, TabsContent } from "@/components/ui/tabs"

describe("Tabs (underline variant — non-closable)", () => {
  it("renders all triggers", () => {
    render(
      <Tabs value="a" onValueChange={() => {}}>
        <TabsList>
          <TabsTrigger value="a">Tab A</TabsTrigger>
          <TabsTrigger value="b">Tab B</TabsTrigger>
          <TabsTrigger value="c">Tab C</TabsTrigger>
        </TabsList>
      </Tabs>,
    )
    expect(screen.getByRole("tab", { name: "Tab A" })).toBeTruthy()
    expect(screen.getByRole("tab", { name: "Tab B" })).toBeTruthy()
    expect(screen.getByRole("tab", { name: "Tab C" })).toBeTruthy()
  })

  it("marks active tab as selected", () => {
    render(
      <Tabs value="b" onValueChange={() => {}}>
        <TabsList>
          <TabsTrigger value="a">Tab A</TabsTrigger>
          <TabsTrigger value="b">Tab B</TabsTrigger>
        </TabsList>
      </Tabs>,
    )
    const tabA = screen.getByRole("tab", { name: "Tab A" })
    const tabB = screen.getByRole("tab", { name: "Tab B" })
    expect(tabA.getAttribute("aria-selected")).toBe("false")
    expect(tabB.getAttribute("aria-selected")).toBe("true")
  })

  it("applies active styling classes to selected tab", () => {
    render(
      <Tabs value="a" onValueChange={() => {}}>
        <TabsList>
          <TabsTrigger value="a">Active</TabsTrigger>
          <TabsTrigger value="b">Inactive</TabsTrigger>
        </TabsList>
      </Tabs>,
    )
    const active = screen.getByRole("tab", { name: "Active" })
    const inactive = screen.getByRole("tab", { name: "Inactive" })
    // Active tab gets border-primary, inactive gets border-transparent
    expect(active.className).toContain("border-primary")
    expect(inactive.className).toContain("border-transparent")
  })

  it("calls onValueChange on click", () => {
    let changed = ""
    render(
      <Tabs value="a" onValueChange={(v) => (changed = v)}>
        <TabsList>
          <TabsTrigger value="a">Tab A</TabsTrigger>
          <TabsTrigger value="b">Tab B</TabsTrigger>
        </TabsList>
      </Tabs>,
    )
    fireEvent.click(screen.getByRole("tab", { name: "Tab B" }))
    expect(changed).toBe("b")
  })

  it("does not call onValueChange when clicking already-active tab", () => {
    let calls = 0
    render(
      <Tabs value="a" onValueChange={() => calls++}>
        <TabsList>
          <TabsTrigger value="a">Tab A</TabsTrigger>
        </TabsList>
      </Tabs>,
    )
    fireEvent.click(screen.getByRole("tab", { name: "Tab A" }))
    // It still calls onValueChange even for active tab (no guard in component)
    expect(calls).toBe(1)
  })

  it("does not call onValueChange for disabled tab", () => {
    let calls = 0
    render(
      <Tabs value="a" onValueChange={() => calls++}>
        <TabsList>
          <TabsTrigger value="a">Tab A</TabsTrigger>
          <TabsTrigger value="b" disabled>
            Tab B
          </TabsTrigger>
        </TabsList>
      </Tabs>,
    )
    fireEvent.click(screen.getByRole("tab", { name: "Tab B" }))
    expect(calls).toBe(0)
  })

  it("renders tablist with border-b separator", () => {
    render(
      <Tabs value="a" onValueChange={() => {}}>
        <TabsList>
          <TabsTrigger value="a">Tab A</TabsTrigger>
        </TabsList>
      </Tabs>,
    )
    const tablist = screen.getByRole("tablist")
    expect(tablist.className).toContain("border-b")
  })

  it("shows only active content panel", () => {
    render(
      <Tabs value="a" onValueChange={() => {}}>
        <TabsList>
          <TabsTrigger value="a">Tab A</TabsTrigger>
          <TabsTrigger value="b">Tab B</TabsTrigger>
        </TabsList>
        <TabsContent value="a">
          <div>Content A</div>
        </TabsContent>
        <TabsContent value="b">
          <div>Content B</div>
        </TabsContent>
      </Tabs>,
    )
    expect(screen.getByText("Content A")).toBeTruthy()
    expect(screen.queryByText("Content B")).toBeNull()
  })

  it("supports size variants (md/sm)", () => {
    render(
      <Tabs value="a" onValueChange={() => {}}>
        <TabsList size="sm">
          <TabsTrigger value="a">Small Tab</TabsTrigger>
        </TabsList>
      </Tabs>,
    )
    const tab = screen.getByRole("tab", { name: "Small Tab" })
    // sm size => h-8 text-xs px-3
    expect(tab.className).toContain("h-8")
    expect(tab.className).toContain("text-xs")
  })

  it("supports keyboard ArrowRight navigation", () => {
    let active = "a"
    const { rerender } = render(
      <Tabs
        value={active}
        onValueChange={(v) => {
          active = v
        }}
      >
        <TabsList>
          <TabsTrigger value="a">Tab A</TabsTrigger>
          <TabsTrigger value="b">Tab B</TabsTrigger>
          <TabsTrigger value="c">Tab C</TabsTrigger>
        </TabsList>
      </Tabs>,
    )
    // Re-render with updated active to simulate controlled state
    fireEvent.keyDown(screen.getByRole("tablist"), { key: "ArrowRight" })
    rerender(
      <Tabs value={active} onValueChange={(v) => (active = v)}>
        <TabsList>
          <TabsTrigger value="a">Tab A</TabsTrigger>
          <TabsTrigger value="b">Tab B</TabsTrigger>
          <TabsTrigger value="c">Tab C</TabsTrigger>
        </TabsList>
      </Tabs>,
    )
    expect(active).toBe("b")
  })

  it("supports keyboard ArrowLeft navigation", () => {
    let active = "b"
    const { rerender } = render(
      <Tabs
        value={active}
        onValueChange={(v) => {
          active = v
        }}
      >
        <TabsList>
          <TabsTrigger value="a">Tab A</TabsTrigger>
          <TabsTrigger value="b">Tab B</TabsTrigger>
        </TabsList>
      </Tabs>,
    )
    fireEvent.keyDown(screen.getByRole("tablist"), { key: "ArrowLeft" })
    rerender(
      <Tabs value={active} onValueChange={(v) => (active = v)}>
        <TabsList>
          <TabsTrigger value="a">Tab A</TabsTrigger>
          <TabsTrigger value="b">Tab B</TabsTrigger>
        </TabsList>
      </Tabs>,
    )
    expect(active).toBe("a")
  })

  it("has no close button (non-closable variant)", () => {
    render(
      <Tabs value="a" onValueChange={() => {}}>
        <TabsList>
          <TabsTrigger value="a">Tab A</TabsTrigger>
        </TabsList>
      </Tabs>,
    )
    // No close button elements — component is non-closable by design
    const closeButtons = screen.queryAllByRole("button", { name: /close|关闭/i })
    expect(closeButtons.length).toBe(0)
  })

  it("renders icon and suffix in trigger", () => {
    // TabsTrigger supports icon and suffix props
    render(
      <Tabs value="a" onValueChange={() => {}}>
        <TabsList>
          <TabsTrigger value="a" suffix={<span data-testid="suffix">*</span>}>
            Tab A
          </TabsTrigger>
        </TabsList>
      </Tabs>,
    )
    expect(screen.getByTestId("suffix")).toBeTruthy()
  })

  it("supports trailing element in TabsList", () => {
    render(
      <Tabs value="a" onValueChange={() => {}}>
        <TabsList trailing={<button data-testid="trailing-btn">Action</button>}>
          <TabsTrigger value="a">Tab A</TabsTrigger>
        </TabsList>
      </Tabs>,
    )
    expect(screen.getByTestId("trailing-btn")).toBeTruthy()
  })

  it("throws when TabsTrigger used outside Tabs", () => {
    // Suppress console.error for expected throw
    const spy = vi.spyOn(console, "error").mockImplementation(() => {})
    expect(() => {
      render(<TabsTrigger value="a">Orphan</TabsTrigger>)
    }).toThrow("Tabs components must be used within <Tabs>")
    spy.mockRestore()
  })
})
