---
name: DataWeave
description: AI Agent 原生数据中台的设计系统
colors:
  background: "oklch(1 0 0)"
  foreground: "oklch(0.147 0.004 49.3)"
  card: "oklch(1 0 0)"
  cardForeground: "oklch(0.147 0.004 49.3)"
  popover: "oklch(1 0 0)"
  popoverForeground: "oklch(0.147 0.004 49.3)"
  primary: "oklch(0.457 0.24 277.023)"
  primaryForeground: "oklch(0.962 0.018 272.314)"
  secondary: "oklch(0.967 0.001 286.375)"
  secondaryForeground: "oklch(0.21 0.006 285.885)"
  muted: "oklch(0.96 0.002 17.2)"
  mutedForeground: "oklch(0.547 0.021 43.1)"
  accent: "oklch(0.96 0.002 17.2)"
  accentForeground: "oklch(0.214 0.009 43.1)"
  destructive: "oklch(0.577 0.245 27.325)"
  border: "oklch(0.922 0.005 34.3)"
  input: "oklch(0.922 0.005 34.3)"
  ring: "oklch(0.714 0.014 41.2)"
  chart1: "oklch(0.855 0.138 181.071)"
  chart2: "oklch(0.704 0.14 182.503)"
  chart3: "oklch(0.6 0.118 184.704)"
  chart4: "oklch(0.511 0.096 186.391)"
  chart5: "oklch(0.437 0.078 188.216)"
colorsDark:
  background: "oklch(0.147 0.004 49.3)"
  foreground: "oklch(0.986 0.002 67.8)"
  card: "oklch(0.214 0.009 43.1)"
  cardForeground: "oklch(0.986 0.002 67.8)"
  popover: "oklch(0.214 0.009 43.1)"
  popoverForeground: "oklch(0.986 0.002 67.8)"
  primary: "oklch(0.398 0.195 277.366)"
  primaryForeground: "oklch(0.962 0.018 272.314)"
  secondary: "oklch(0.274 0.006 286.033)"
  secondaryForeground: "oklch(0.985 0 0)"
  muted: "oklch(0.268 0.011 36.5)"
  mutedForeground: "oklch(0.714 0.014 41.2)"
  accent: "oklch(0.268 0.011 36.5)"
  accentForeground: "oklch(0.986 0.002 67.8)"
  destructive: "oklch(0.704 0.191 22.216)"
  border: "oklch(1 0 0 / 10%)"
  input: "oklch(1 0 0 / 15%)"
  ring: "oklch(0.547 0.021 43.1)"
rounded:
  base: "0.625rem"
typography:
  fontSans: "var(--font-sans)"
  fontHeading: "var(--font-sans)"
---

## Overview

DataWeave 的设计系统。本文件是**主题真相源**：颜色、圆角、字体的取值集中在上方 YAML tokens 中，配以下文的设计理据。运行 `npx @google/design.md lint DESIGN.md` 校验结构，`npx @google/design.md export` 可导出为 CSS 变量 / Tailwind config。

> 实际生效的 CSS 变量位于 `app/globals.css`，由 shadcn preset **`bPbxE81FT`** 统一管理。当前：base color **taupe**（暖灰中性）+ **紫色 primary** + **青绿 chart**，style `base-maia`，radius 0.625rem。Tailwind v4 通过 `@theme inline` 把这些变量映射为色板 token（`bg-primary`、`text-muted-foreground` 等）。换主题统一用 `pnpm dlx shadcn@latest apply --preset <code>`，再把上方 tokens 与本文同步。

## 色彩

- **primary**：靛紫（`oklch(0.457 0.24 277)`，色相 ~277），承载品牌与主操作（发送、上线、确认）。暗色下降明度与饱和度，避免刺眼。
- **中性色阶：taupe（暖灰，色相 ~17–49）**。亮色近纯白底 + 近黑前景；暗色为暖灰褐底 + 近白前景。暖中性与紫色 primary、青绿 chart 搭配，整体沉稳，契合数据中台长时间注视的低疲劳诉求。
- **muted / accent**：极低饱和的暖灰，用于次级信息、占位、hover 面，不与 primary 抢视觉。
- **destructive**：红色，仅用于删除/阻断/失败态（如质量阻断、任务失败）。
- **chart-1..5**：青绿系渐进色阶（teal，色相 181→188），给指标趋势、血缘图、运行统计等可视化使用，保证同一图表内色相一致、可区分。与紫色 primary 形成对比。
- **sidebar-***：侧边导航独立一套 token，使中台左栏与主区在亮/暗下都有清晰层次。

## 圆角

`--radius = 0.625rem`，适中圆角，干净利落的中台观感；`globals.css` 据此派生 sm/md/lg/xl 等梯度。

## 字体

正文与标题统一走 `--font-sans`（Next.js `next/font` 注入），保证界面信息密度高时仍清晰。

## 布局：无分割线（项目偏好）

页面采用 **header / content(body) / footer** 结构。**header 与 content、content 与 footer 之间不放任何分割线**——不要 `border-b` / `border-t` / 横向 `<Separator>` / `<hr>`。靠留白（padding/间距）和背景层次区分区域，不靠线。

- ❌ `<header className="... border-b ...">`、footer 上的 `border-t`、区域之间的横向 `Separator`。
- ✅ header 直接是 `flex h-14 items-center gap-2 px-4`（无边框），content 紧随其后。
- 此规则适用于所有页面与 shell，新增布局一律遵守。

## 用法约束（与 shadcn 规则一致）

- 用语义 token，不写裸色值：`bg-primary` 而非 `bg-[#...]`。
- 暗色靠 `.dark` 下的变量覆盖，不手写 `dark:` 颜色覆盖。
- 间距用 `gap-*`，等宽高用 `size-*`。
