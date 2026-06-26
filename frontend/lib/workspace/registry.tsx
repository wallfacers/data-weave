"use client"

/**
 * 视图注册表：viewType → 图标 + 组件。
 * 元数据（标题/defaultPinned）在 views.ts（纯数据，供 store 与测试）；
 * 这里只补 React 侧的渲染映射。新增视图：两处各加一行。
 */
import type { ComponentType } from "react"
import { useTranslations } from "next-intl"
import type { IconSvgElement } from "@hugeicons/react"
import {
  Activity02Icon,
  Analytics01Icon,
  BugIcon,
  CatalogueIcon,
  DashboardSquare01Icon,
  Database01Icon,
  DatabaseSyncIcon,
  DocumentCodeIcon,
  Flowchart01Icon,
  GitBranchIcon,
  RefreshIcon,
  ServerStackIcon,
  SettingDone02Icon,
  Settings02Icon,
  ServiceIcon,
  Shield01Icon,
  Share08Icon,
} from "@hugeicons/core-free-icons"

import { OpsView } from "@/components/workspace/views/ops-view"
import { FreshnessView } from "@/components/workspace/views/freshness-view"
import { ReportsView } from "@/components/workspace/views/reports-view"
import { WorkflowCanvasView } from "@/components/workspace/views/workflow-canvas-view"
import { FleetView } from "@/components/workspace/views/fleet-view"
import { SettingsView } from "@/components/workspace/views/settings-view"
import { InstanceLogView } from "@/components/workspace/views/instance-log-view"
import { WorkflowInstanceDetail } from "@/components/workspace/views/workflow-instance-detail"
import { MetricsView } from "@/components/workspace/views/metrics-view"
import { DatasourcesView } from "@/components/workspace/views/datasources-view"
import { PlaceholderView } from "@/components/workspace/views/placeholder-view"
import { type ViewType } from "./views"

export interface ViewProps {
  params?: Record<string, unknown>
}

interface ViewRender {
  icon: IconSvgElement
  component: ComponentType<ViewProps>
}

/** 占位视图工厂：标题复用 `views.<viewKey>`，描述取 `placeholderView.<descKey>`，均按 UI locale。 */
const placeholder = (viewKey: string, descKey: string) => {
  const View = () => {
    const tv = useTranslations("views")
    const tp = useTranslations("placeholderView")
    return <PlaceholderView title={tv(viewKey)} description={tp(descKey)} />
  }
  View.displayName = `Placeholder(${viewKey})`
  return View
}

export const VIEW_RENDER: Record<ViewType, ViewRender> = {
  ops: { icon: SettingDone02Icon, component: OpsView },
  "workflow-canvas": { icon: Share08Icon, component: WorkflowCanvasView },
  freshness: { icon: RefreshIcon, component: FreshnessView },
  reports: { icon: Analytics01Icon, component: ReportsView },
  metrics: { icon: Activity02Icon, component: MetricsView },
  fleet: { icon: ServerStackIcon, component: FleetView },
  "instance-log": { icon: DocumentCodeIcon, component: InstanceLogView },
  "workflow-instance-detail": { icon: Flowchart01Icon, component: WorkflowInstanceDetail },
  lineage: {
    icon: GitBranchIcon,
    component: placeholder("lineage", "descLineage"),
  },
  catalog: {
    icon: CatalogueIcon,
    component: placeholder("catalog", "descCatalog"),
  },
  quality: {
    icon: Shield01Icon,
    component: placeholder("quality", "descQuality"),
  },
  integration: {
    icon: DatabaseSyncIcon,
    component: placeholder("integration", "descIntegration"),
  },
  datasources: {
    icon: Database01Icon,
    component: DatasourcesView,
  },
  service: {
    icon: ServiceIcon,
    component: placeholder("service", "descService"),
  },
  settings: {
    icon: Settings02Icon,
    component: SettingsView,
  },
}
