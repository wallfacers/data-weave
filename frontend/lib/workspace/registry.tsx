"use client"

/**
 * 视图注册表：viewType → 图标 + 组件。
 * 元数据（标题/defaultPinned）在 views.ts（纯数据，供 store 与测试）；
 * 这里只补 React 侧的渲染映射。新增视图：两处各加一行。
 */
import type { ComponentType } from "react"
import type { IconSvgElement } from "@hugeicons/react"
import {
  Analytics01Icon,
  BugIcon,
  Calendar01Icon,
  CatalogueIcon,
  DashboardSquare01Icon,
  DatabaseSyncIcon,
  GitBranchIcon,
  RefreshIcon,
  ServerStackIcon,
  ServiceIcon,
  Shield01Icon,
  WorkflowSquare01Icon,
} from "@hugeicons/core-free-icons"

import { CockpitView } from "@/components/workspace/views/cockpit-view"
import { FreshnessView } from "@/components/workspace/views/freshness-view"
import { ReportsView } from "@/components/workspace/views/reports-view"
import { TaskFlowView } from "@/components/workspace/views/task-flow-view"
import { DiagnosisView } from "@/components/workspace/views/diagnosis-view"
import { FleetView } from "@/components/workspace/views/fleet-view"
import { SqlWorkbenchView } from "@/components/workspace/views/sql-workbench-view"
import { PlaceholderView } from "@/components/workspace/views/placeholder-view"
import { type ViewType } from "./views"

export interface ViewProps {
  params?: Record<string, unknown>
}

interface ViewRender {
  icon: IconSvgElement
  component: ComponentType<ViewProps>
}

const placeholder = (title: string, description: string) => {
  const View = () => <PlaceholderView title={title} description={description} />
  View.displayName = `Placeholder(${title})`
  return View
}

export const VIEW_RENDER: Record<ViewType, ViewRender> = {
  cockpit: { icon: DashboardSquare01Icon, component: CockpitView },
  "task-flow": { icon: Calendar01Icon, component: TaskFlowView },
  freshness: { icon: RefreshIcon, component: FreshnessView },
  reports: { icon: Analytics01Icon, component: ReportsView },
  "sql-workbench": { icon: WorkflowSquare01Icon, component: SqlWorkbenchView },
  diagnosis: { icon: BugIcon, component: DiagnosisView },
  fleet: { icon: ServerStackIcon, component: FleetView },
  lineage: {
    icon: GitBranchIcon,
    component: placeholder("数据血缘", "表与任务的上下游依赖图谱。"),
  },
  catalog: {
    icon: CatalogueIcon,
    component: placeholder("资产目录", "数据表、主题域与资产盘点。"),
  },
  quality: {
    icon: Shield01Icon,
    component: placeholder("数据质量", "质量规则、校验结果与告警。"),
  },
  integration: {
    icon: DatabaseSyncIcon,
    component: placeholder("数据集成", "数据源接入与同步链路。"),
  },
  service: {
    icon: ServiceIcon,
    component: placeholder("数据服务", "数据 API 与服务编排。"),
  },
}
