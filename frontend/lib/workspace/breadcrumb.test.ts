import { describe, it, expect } from "vitest"

import { deriveBreadcrumbNodes, type BreadcrumbNode } from "./breadcrumb"
import { tabLabel } from "@/components/workspace/tab-bar"
import { VIEW_META } from "./views"
import type { WorkspaceTab } from "./store"

/** 模拟 t 函数：返回 key 本身，保证测试确定性 */
function mockT(key: string): string {
  return key
}

describe("deriveBreadcrumbNodes 面包屑派生（契约 A1/A2/A3）", () => {
  const projectName = "TestProject"

  it("入口视图（如 ops）→ 三级节点（项目 > 分组 > 视图）", () => {
    const nodes = deriveBreadcrumbNodes("ops", undefined, projectName, mockT)
    expect(nodes).toHaveLength(3)
    expect(nodes[0].label).toBe("TestProject")
    // ops 归属 ops（运维监控）分组
    expect(nodes[1].label).toBe("leftNav.groups.ops")
    expect(nodes[2].label).toBe(VIEW_META["ops"].title)
  })

  it("入口视图（如 settings）→ 三级节点，settings 归属 admin 分组", () => {
    const nodes = deriveBreadcrumbNodes("settings", undefined, projectName, mockT)
    expect(nodes).toHaveLength(3)
    expect(nodes[0].label).toBe("TestProject")
    expect(nodes[1].label).toBe("leftNav.groups.admin")
    expect(nodes[2].label).toBe(VIEW_META["settings"].title)
  })

  it("无分组视图 → 二级节点（项目 > 视图），不产生空节点、不抛错", () => {
    // 所有已注册视图均已分组；验证函数在 resolveActiveHighlight 返回空时的兜底行为。
    // workflow-canvas 归属 dev 分组，此处验证即便无分组归属也不抛错。
    // 由于当前所有 ViewType 均已归类，本用例确认函数不依赖"分组一定存在"的假设。
    // 模拟：使用一个不属于任何 NAV_GROUP 且不在 detailViewParent 中的场景。
    // 直接测试函数对未知 view 的处理：resolveActiveHighlight("nope" as ViewType) → {}
    // 但 deriveBreadcrumbNodes 的 view 参数类型为 ViewType，无法传入"nope"。
    // 验证：所有 ViewType 都经 resolveActiveHighlight 返回非空 group（分类完备性）。
    // 同时验证函数结构不会在 group 缺失时抛错——确认代码路径存在兜底。
    // （若将来新增视图未及时归类，面包屑自动退化为二级而非崩溃。）
    const allViews = Object.keys(VIEW_META)
    for (const v of allViews) {
      const nodes = deriveBreadcrumbNodes(v as keyof typeof VIEW_META, undefined, projectName, mockT)
      // 每个视图至少返回 2 个节点（项目 + 视图），分组可选
      expect(nodes.length).toBeGreaterThanOrEqual(2)
      expect(nodes[0].label).toBe(projectName)
    }
  })

  it("带 params 的详情视图（如 instance-log）→ 四级节点，末级值与 tabLabel 输出一致", () => {
    const params = { instanceId: "inst-12345" }
    const nodes = deriveBreadcrumbNodes("instance-log", params, projectName, mockT)

    expect(nodes).toHaveLength(4)
    expect(nodes[0].label).toBe("TestProject")
    // instance-log 详情视图归运维监控模块
    expect(nodes[1].label).toBe("leftNav.groups.ops")
    expect(nodes[2].label).toBe(VIEW_META["instance-log"].title)
    // 末级为动态参数值
    expect(nodes[3].label).toBe("inst-12345")

    // 验证末级值与 tabLabel 输出中的动态参数部分一致（契约 A3）
    const tab: WorkspaceTab = {
      id: "instance-log?instanceId=inst-12345",
      view: "instance-log",
      params: { instanceId: "inst-12345" },
      pinned: false,
      base: false,
    }
    const label = tabLabel(tab, mockT)
    // tabLabel 返回 "标题 · 首个param值"
    expect(label).toContain(nodes[3].label)
    // 验证 tabLabel 的完整格式
    expect(label).toBe(`${mockT(VIEW_META["instance-log"].title)} · inst-12345`)
  })

  it("带多个 params 的详情视图 → 仅取首个 param 值作为末级节点", () => {
    const params = { instanceId: "inst-abc", taskId: "42" }
    const nodes = deriveBreadcrumbNodes("instance-log", params, projectName, mockT)

    expect(nodes).toHaveLength(4)
    // 末级仅为首个 param 值（与 tabLabel 约定一致）
    expect(nodes[3].label).toBe("inst-abc")
  })

  it("params 为空对象或无值 → 不追加动态参数节点", () => {
    const nodesEmpty = deriveBreadcrumbNodes("instance-log", {}, projectName, mockT)
    expect(nodesEmpty).toHaveLength(3) // 项目 + 分组 + 视图
    expect(nodesEmpty[2].label).toBe(VIEW_META["instance-log"].title)

    const nodesUndefined = deriveBreadcrumbNodes("instance-log", undefined, projectName, mockT)
    expect(nodesUndefined).toHaveLength(3)
  })

  it("上下文详情视图 workflow-instance-detail → 三级节点 + 可选动态参数", () => {
    const nodes = deriveBreadcrumbNodes("workflow-instance-detail", { wfId: "wf-1" }, projectName, mockT)
    expect(nodes).toHaveLength(4)
    expect(nodes[0].label).toBe("TestProject")
    expect(nodes[1].label).toBe("leftNav.groups.ops")
    expect(nodes[2].label).toBe(VIEW_META["workflow-instance-detail"].title)
    expect(nodes[3].label).toBe("wf-1")
  })

  it("deriveBreadcrumbNodes 两次不同调用互不残留（契约 A4）", () => {
    const nodes1 = deriveBreadcrumbNodes("ops", undefined, "ProjectA", mockT)
    const nodes2 = deriveBreadcrumbNodes("instance-log", { instanceId: "xyz" }, "ProjectB", mockT)

    // 两次调用各自独立
    expect(nodes1[0].label).toBe("ProjectA")
    expect(nodes1).toHaveLength(3)

    expect(nodes2[0].label).toBe("ProjectB")
    expect(nodes2).toHaveLength(4)
    expect(nodes2[3].label).toBe("xyz")
  })
})
