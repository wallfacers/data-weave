import { describe, it, expect } from "vitest"
import { dagViewToFlow } from "./dag-helpers"

describe("dagViewToFlow", () => {
  it("maps nodes and edges to ReactFlow format", () => {
    const dag = {
      nodes: [
        { nodeKey: "v0", nodeType: "VIRTUAL", taskId: null, name: "开始", posX: 0, posY: 0, taskStatus: null },
        { nodeKey: "t1", nodeType: "TASK", taskId: 1, name: "任务1", posX: 100, posY: 0, taskStatus: "ONLINE" },
        { nodeKey: "t2", nodeType: "TASK", taskId: 2, name: "任务2", posX: 200, posY: 0, taskStatus: "ONLINE" },
      ],
      edges: [
        { fromNodeKey: "v0", toNodeKey: "t1", strength: "STRONG" },
        { fromNodeKey: "t1", toNodeKey: "t2", strength: "WEAK" },
      ],
    }

    const { nodes, edges } = dagViewToFlow(dag)

    expect(nodes).toHaveLength(3)
    expect(nodes[0].id).toBe("v0")
    expect(nodes[0].type).toBe("virtual")
    expect(nodes[0].data.label).toBe("开始")
    expect(nodes[1].type).toBe("task")
    expect(nodes[1].data.taskId).toBe(1)

    expect(edges).toHaveLength(2)
    expect(edges[0].source).toBe("v0")
    expect(edges[0].target).toBe("t1")
    // WEAK edges should be animated and dashed
    expect(edges[1].animated).toBe(true)
    expect(edges[1].style).toEqual({ strokeDasharray: "6 4" })
  })

  it("handles empty nodes and edges", () => {
    const dag = { nodes: [], edges: [] }
    const { nodes, edges } = dagViewToFlow(dag)
    expect(nodes).toHaveLength(0)
    expect(edges).toHaveLength(0)
  })

  it("defaults missing strength to STRONG", () => {
    const dag = {
      nodes: [{ nodeKey: "v0", nodeType: "VIRTUAL", taskId: null, name: "入口", posX: 0, posY: 0, taskStatus: null }],
      edges: [{ fromNodeKey: "v0", toNodeKey: "t1" }],
    }
    const { edges } = dagViewToFlow(dag)
    expect(edges[0].animated).toBeUndefined()
  })

  it("defaults missing posX/posY to 0", () => {
    const dag = {
      nodes: [{ nodeKey: "v0", nodeType: "VIRTUAL", taskId: null, name: "入口", posX: null, posY: null, taskStatus: null }],
      edges: [],
    }
    const { nodes } = dagViewToFlow(dag)
    expect(nodes[0].position).toEqual({ x: 0, y: 0 })
  })
})
