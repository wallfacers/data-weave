import { describe, it, expect, beforeEach, vi, afterEach } from "vitest"

// mock 依赖：listProjects + workspace store
const listProjectsMock = vi.fn()
vi.mock("@/lib/project-api", () => ({
  listProjects: () => listProjectsMock(),
}))

const closeManyMock = vi.fn()
vi.mock("@/lib/workspace/store", () => ({
  useWorkspaceStore: {
    getState: () => ({ closeMany: closeManyMock }),
  },
}))

import { useProjectContext, currentProjectId } from "./project-context"

const P = (id: number, name = `p${id}`) => ({ id, name, code: name, status: "ACTIVE" })

// vitest 默认 node 环境无 window/localStorage —— store 持久化以 `typeof window` 守卫；
// stub truthy window + 内存版 localStorage 才能驱动持久化路径。
function fakeLocalStorage() {
  const m = new Map<string, string>()
  return {
    getItem: (k: string) => (m.has(k) ? (m.get(k) as string) : null),
    setItem: (k: string, v: string) => void m.set(k, String(v)),
    removeItem: (k: string) => void m.delete(k),
    clear: () => m.clear(),
  }
}

function resetStore() {
  useProjectContext.setState({ currentProjectId: null, projects: [], status: "idle" })
}

describe("project-context", () => {
  let ls: ReturnType<typeof fakeLocalStorage>
  beforeEach(() => {
    ls = fakeLocalStorage()
    vi.stubGlobal("localStorage", ls)
    vi.stubGlobal("window", { localStorage: ls })
    listProjectsMock.mockReset()
    closeManyMock.mockReset()
    resetStore()
  })
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it("loadProjects 无持久化时默认取列表首个（FR-019）", async () => {
    listProjectsMock.mockResolvedValue([P(7), P(8), P(9)])
    await useProjectContext.getState().loadProjects()
    const s = useProjectContext.getState()
    expect(s.status).toBe("ready")
    expect(s.currentProjectId).toBe(7)
    expect(localStorage.getItem("dw.project.current")).toBe("7")
  })

  it("loadProjects 持久化值有效时恢复该项目（FR-015）", async () => {
    useProjectContext.setState({ currentProjectId: 8 })
    listProjectsMock.mockResolvedValue([P(7), P(8), P(9)])
    await useProjectContext.getState().loadProjects()
    expect(useProjectContext.getState().currentProjectId).toBe(8)
  })

  it("loadProjects 持久化值失效时回退首个", async () => {
    useProjectContext.setState({ currentProjectId: 999 })
    listProjectsMock.mockResolvedValue([P(7), P(8)])
    await useProjectContext.getState().loadProjects()
    expect(useProjectContext.getState().currentProjectId).toBe(7)
  })

  it("空列表 → empty 态（FR-017）", async () => {
    listProjectsMock.mockResolvedValue([])
    await useProjectContext.getState().loadProjects()
    expect(useProjectContext.getState().status).toBe("empty")
  })

  it("加载失败 → error 态（FR-017）", async () => {
    listProjectsMock.mockRejectedValue(new Error("boom"))
    await useProjectContext.getState().loadProjects()
    expect(useProjectContext.getState().status).toBe("error")
  })

  it("setProject 更新上下文、写 localStorage、并关闭带参数标签（FR-018）", () => {
    useProjectContext.getState().setProject(42)
    expect(useProjectContext.getState().currentProjectId).toBe(42)
    expect(localStorage.getItem("dw.project.current")).toBe("42")
    expect(closeManyMock).toHaveBeenCalledTimes(1)
    // 谓词应关闭带 params 的标签、保留无 params 的
    const victim = closeManyMock.mock.calls[0][0] as (t: { params?: unknown }) => boolean
    expect(victim({ params: { instanceId: 1 } })).toBe(true)
    expect(victim({ params: undefined })).toBe(false)
  })

  it("setProject 同值不触发副作用", () => {
    useProjectContext.setState({ currentProjectId: 5 })
    useProjectContext.getState().setProject(5)
    expect(closeManyMock).not.toHaveBeenCalled()
  })

  it("currentProjectId() 未就绪回退 1", () => {
    expect(currentProjectId()).toBe(1)
    useProjectContext.setState({ currentProjectId: 3 })
    expect(currentProjectId()).toBe(3)
  })
})
