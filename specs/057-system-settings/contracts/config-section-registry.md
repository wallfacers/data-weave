# Contract: 配置分区注册表（Config Shell · 可复用外壳）

**Feature**: 057-system-settings | 「配置」tab 内部「左导航 + 右内容」可复用外壳

## 注册表（纯数据）

`frontend/lib/workspace/settings/config-sections.ts`：

```ts
import type { FC } from "react"
import type { HugeIcon } from "@hugeicons/core-free-icons"

export interface ConfigSection {
  id: string                       // 稳定标识（唯一）
  titleKey: string                 // i18n key（settingsView.* 命名空间）
  icon: HugeIcon                   // 分区图标
  requirePermission?: string       // 可选；缺省=继承 settings 的 project:manage 可见性
  component: FC                    // 右侧内容组件（内联渲染，自带数据获取/保存）
}

export const CONFIG_SECTIONS: ConfigSection[]

/** 按当前权限过滤可见分区（对齐 nav-groups.ts 的 filterVisibleItems 模式）。 */
export function filterVisibleSections(permissions: ReadonlySet<string>): ConfigSection[]
```

**首项**：`{ id: "ai-agent", titleKey: "settingsView.configSectionAiAgent", icon: AiBrainIcon, component: AiAgentConfigSection }`

**不变量（配 `config-sections.test.ts`）**：
- `id` 全局唯一；
- 每个 `component` 必须可内联渲染（自带 GET/PUT/test 数据流，不依赖外部 prop 传配置）；
- `filterVisibleSections` 为纯函数（无 React/网络依赖，可在 node 测试环境直接断言）。

## ConfigShell 契约（`settings/config-shell.tsx`）

- **props**: 无（自管 `activeSectionId`，localStorage 记忆上次分区，默认首项）。
- **行为**：左侧 `ConfigNav` 渲染 `filterVisibleSections(当前权限)`；选中分区 → 右侧 `DwScroll` 内渲染其 `component`。
- **布局**：复用 `DataDevIdeShell` 模式——左可拖拽调宽卡片（`motion` + localStorage 持久化，宽 256/180–480）+ 右圆角卡片内容区（`flex h-full gap-3 p-3`、`rounded-[var(--radius-lg)] border bg-card shadow-lg`、`DwScroll`、`--card-spacing`、**无分割线**）。
- **合规**：左导航是 nav rail（非 `Tabs` 组件，不触发双风格规则）；选中态 `bg-muted font-medium text-foreground`，未选中 `text-muted-foreground hover:text-foreground hover:bg-muted/50`；hugeicons 图标。

## ConfigNav 契约（`settings/config-nav.tsx`）

- **props**: `{ sections: ConfigSection[]; activeId: string; onSelect: (id:string)=>void }`。
- **渲染**：`DwScroll` 内逐项 button（`role` 语义、键盘可达），icon + 本地化标题；无手写分割线。

## 扩展点（SC-006）

新增一种全局配置 = 在 `CONFIG_SECTIONS` 加一项 + 实现其 `component`；`ConfigShell` / `ConfigNav` / 注册表测试**零改动**。

## 反例（禁止）
- ❌ 在 `ConfigShell` 内硬编码分区列表（必须走注册表）。
- ❌ 分区 `component` 用 `Dialog` 弹窗承载（须内联渲染于右侧内容区）。
- ❌ 手写左导航分割线 / `border-r`（DESIGN 无分割线规则）。
