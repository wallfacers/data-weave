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
  Alert02Icon,
  BellIcon,
  DashboardSquare01Icon,
  Database01Icon,
  DocumentCodeIcon,
  Flowchart01Icon,
  GitBranchIcon,
  RefreshIcon,
  ServerStackIcon,
  SettingDone02Icon,
  Settings02Icon,
  Share08Icon,
} from "@hugeicons/core-free-icons"

import { OpsView } from "@/components/workspace/views/ops-view"
import { FreshnessView } from "@/components/workspace/views/freshness-view"
import { WorkflowCanvasView } from "@/components/workspace/views/workflow-canvas-view"
import { FleetView } from "@/components/workspace/views/fleet-view"
import { SettingsView } from "@/components/workspace/views/settings-view"
import { MetricsView } from "@/components/workspace/views/metrics-view"
import { DatasourcesView } from "@/components/workspace/views/datasources-view"
import { LineageView } from "@/components/workspace/views/lineage-view"
import { AlertsView } from "@/components/workspace/views/alerts-view"
import { InstanceLogView } from "@/components/workspace/views/instance-log-view"
import { WorkflowInstanceDetail } from "@/components/workspace/views/workflow-instance-detail"
import { type ViewType } from "./views"

export interface ViewProps {
  params?: Record<string, unknown>
  /** 本视图是否当前激活 tab（由 workspace 下传） */
  active?: boolean
}

interface ViewRender {
  icon: IconSvgElement
  component: ComponentType<ViewProps>
}

export const VIEW_RENDER: Record<ViewType, ViewRender> = {
  ops: { icon: SettingDone02Icon, component: OpsView },
  "workflow-canvas": { icon: Share08Icon, component: WorkflowCanvasView },
  freshness: { icon: RefreshIcon, component: FreshnessView },
  metrics: { icon: Activity02Icon, component: MetricsView },
  fleet: { icon: ServerStackIcon, component: FleetView },
  lineage: {
    icon: GitBranchIcon,
    component: LineageView,
  },
  alerts: {
    icon: BellIcon,
    component: AlertsView,
  },
  datasources: {
    icon: Database01Icon,
    component: DatasourcesView,
  },
  settings: {
    icon: Settings02Icon,
    component: SettingsView,
  },
  "instance-log": {
    icon: DocumentCodeIcon,
    component: InstanceLogView,
  },
  "workflow-instance-detail": {
    icon: Flowchart01Icon,
    component: WorkflowInstanceDetail,
  },
}
