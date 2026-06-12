import type { ComponentType } from "react"
import type { IconSvgElement } from "@hugeicons/react"
import { Edit02Icon } from "@hugeicons/core-free-icons"

import { TaskEditPanel } from "@/components/ops/task-edit-panel"

export type SidePanelViewType = "task-edit"

export interface SidePanelViewProps {
  params?: Record<string, unknown>
  onClose: () => void
}

export interface SidePanelViewRender {
  icon: IconSvgElement
  component: ComponentType<SidePanelViewProps>
}

export const SIDE_PANEL_VIEW_RENDER: Record<SidePanelViewType, SidePanelViewRender> = {
  "task-edit": { icon: Edit02Icon, component: TaskEditPanel },
}

export function isKnownSidePanelView(view: string): view is SidePanelViewType {
  return view in SIDE_PANEL_VIEW_RENDER
}
