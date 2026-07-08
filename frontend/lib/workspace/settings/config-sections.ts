/**
 * 057 系统设置「配置」tab 分区注册表（纯数据 + 纯过滤函数，可在 node 测试环境直接 import）。
 *
 * 与 nav-groups.ts 同构：分区项含稳定 id / i18n titleKey / hugeicons icon / 可选权限码 / 右侧内容组件。
 * 不变量（见 config-sections.test.ts）：id 唯一；component 可内联渲染（自带数据获取/保存）。
 *
 * 扩展（SC-006）：新增一种全局配置 = 在 CONFIG_SECTIONS 加一项 + 实现其 component；
 * ConfigShell / ConfigNav 零改动。
 */
import type { FC } from "react"
import { CpuIcon } from "@hugeicons/core-free-icons"
import type { IconSvgElement } from "@hugeicons/react"
import { AiAgentConfigSection } from "@/components/workspace/views/settings/ai-agent-config-section"

export interface ConfigSection {
  /** 稳定标识（唯一） */
  id: string
  /** i18n key（settingsView.* 命名空间） */
  titleKey: string
  /** 分区图标（hugeicons） */
  icon: IconSvgElement
  /** 可选权限码；缺省 = 继承系统设置的 project:manage 可见性，恒可见 */
  requirePermission?: string
  /** 右侧内容组件（内联渲染，自带 GET/PUT/test 数据流） */
  component: FC
}

/** 有序分区目录（决定左导航显示顺序）。 */
export const CONFIG_SECTIONS: ConfigSection[] = [
  {
    id: "ai-agent",
    titleKey: "settingsView.configSectionAiAgent",
    icon: CpuIcon,
    component: AiAgentConfigSection,
  },
]

/**
 * 按当前权限过滤可见分区（无 requirePermission 恒可见）。
 * 纯函数（不依赖 React/网络），可在 node 测试环境直接断言。
 */
export function filterVisibleSections(
  sections: readonly ConfigSection[],
  permissions: ReadonlySet<string>,
): ConfigSection[] {
  return sections.filter((s) => !s.requirePermission || permissions.has(s.requirePermission))
}
