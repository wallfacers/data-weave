"use client"

import { createContext } from "react"

/** 画布节点操作接口（仅编辑态画布提供；只读 DAG 查看器不提供该 context）。 */
export interface NodeActions {
  /** 查看任务日志。 */
  onViewLog: (taskDefId: number, label: string) => void
  /** 运行单个 TASK 节点。 */
  onRunNode: (taskDefId: number, label: string) => void
  /** 运行到该节点（TO_NODE：本节点 + 其全部前驱闭包）。 */
  onRunToNode: (nodeKey: string) => void
  /** 运行该节点的全部下游（DOWNSTREAM 后继闭包）。 */
  onRunDownstream: (nodeKey: string) => void
  /** 工作流是否已上线（未上线时禁用子图运行菜单）。 */
  online: boolean
  /** 删除节点（及其关联边）。 */
  onDeleteNode: (id: string) => void
  /** 该 TASK 节点是否已有可看日志的运行实例。 */
  canViewLog: (taskDefId: number) => boolean
}

export const NodeActionsContext = createContext<NodeActions | null>(null)
