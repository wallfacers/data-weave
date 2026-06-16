import type { ComponentType } from "react"
import type { IconSvgElement } from "@hugeicons/react"

export interface SidePanelViewProps {
  params?: Record<string, unknown>
  onClose: () => void
}

export interface SidePanelViewRender {
  icon: IconSvgElement
  component: ComponentType<SidePanelViewProps>
}

/**
 * 侧栏视图注册表。原「task-edit」已迁入「数据开发」IDE 的编辑子 Tab（task-editor-pane），
 * 本表暂为空——保留通用侧栏基础设施（store + SidePanel 组件，app-shell 消费），供后续侧栏视图复用。
 */
export const SIDE_PANEL_VIEW_RENDER: Record<string, SidePanelViewRender> = {}
