"use client"

/**
 * 视图注册表：viewType → 图标 + 组件。
 * 元数据（标题/defaultPinned）在 views.ts（纯数据，供 store 与测试）；
 * 这里只补 React 侧的渲染映射。新增视图：两处各加一行。
 */
import type { ComponentType } from "react"
import type { IconSvgElement } from "@hugeicons/react"
import {
  Activity02Icon,
  Analytics01Icon,
  BugIcon,
  CatalogueIcon,
  DashboardSquare01Icon,
  DatabaseSyncIcon,
  DocumentCodeIcon,
  Flowchart01Icon,
  GitBranchIcon,
  RefreshIcon,
  ServerStackIcon,
  Settings02Icon,
  ServiceIcon,
  Shield01Icon,
  Share08Icon,
} from "@hugeicons/core-free-icons"

import { CockpitView } from "@/components/workspace/views/cockpit-view"
import { FreshnessView } from "@/components/workspace/views/freshness-view"
import { ReportsView } from "@/components/workspace/views/reports-view"
import { WorkflowCanvasView } from "@/components/workspace/views/workflow-canvas-view"
import { DiagnosisView } from "@/components/workspace/views/diagnosis-view"
import { FleetView } from "@/components/workspace/views/fleet-view"
import { SettingsView } from "@/components/workspace/views/settings-view"
import { InstanceLogView } from "@/components/workspace/views/instance-log-view"
import { WorkflowInstanceDetail } from "@/components/workspace/views/workflow-instance-detail"
import { MetricsView } from "@/components/workspace/views/metrics-view"
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
  "workflow-canvas": { icon: Share08Icon, component: WorkflowCanvasView },
  freshness: { icon: RefreshIcon, component: FreshnessView },
  reports: { icon: Analytics01Icon, component: ReportsView },
  metrics: { icon: Activity02Icon, component: MetricsView },
  diagnosis: { icon: BugIcon, component: DiagnosisView },
  fleet: { icon: ServerStackIcon, component: FleetView },
  "instance-log": { icon: DocumentCodeIcon, component: InstanceLogView },
  "workflow-instance-detail": { icon: Flowchart01Icon, component: WorkflowInstanceDetail },
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
  settings: {
    icon: Settings02Icon,
    component: SettingsView,
  },
}
