# Data Model：清理对象清单（014）

本特性不引入或修改任何持久化实体、不改数据库 schema、不改同步 API 传输形态。"模型"= 待处置的清理对象及其判定依据与处置动作。

## 清理对象

| 对象 | 类型 | 判定依据 | 处置 | 关联 US |
|---|---|---|---|---|
| `ops-alert-card.tsx` | 前端组件 | 举手台告警卡片，仅渲染 mock store | 删除 | US1 |
| `ops-alerts-store.ts` | 前端 zustand store | 唯一写入=mock 注入，后端无真源 | 删除 | US1 |
| ops-view 右栏 rail | 前端布局片段 | 承载举手台，移除后主舞台回填 | 移除（保布局完整） | US1 |
| `useMockAlertInjector` + `window.__MOCK_OPS_ALERT__` | 前端 mock 注入器 | 喂养举手台的开发注入点 | 删除（不留死注入点） | US1 |
| side-panel/backfill-dialog/workflow-canvas/layout 等触点 | 前端悬空引用 | grep 命中举手台符号者 | 核查后清理（真引用才动） | US1 |
| ops 区硬编码中文 UI 串（已知 5 + sweep 补漏） | 前端文案 | 未走 `t()` 的静态 UI 文案 | 迁入 next-intl 双 bundle | US2 |
| `messages/{zh-CN,en-US}.json` ops 命名空间 | i18n 文案集 | 漏迁项 + 键平价 | 补键（同键集、ICU 插值） | US2 |
| `backend/dataweave-alert/` | 后端目录 | 0 tracked、target gitignore、不在根 modules | 删除目录 | US3 |
| docs/architecture.md 血缘小节 | 仓库文档 | 缺"push 不落血缘"记载 | 新增有意延迟记载 | US4 |
| CLAUDE.md 血缘导航行 | 仓库文档 | 缺缺口注记 | 加注记 | US4 |

## 不变量（MUST）

- **INV-1 移除不伤可观测性**：举手台移除 MUST NOT 影响 ops overview / metrics / 运行日志 / DAG 实例视图（Principle IV 红线）。举手台是右栏告警 rail，与主舞台能力解耦。
- **INV-2 零悬空引用**：移除后全仓无指向已删举手台符号的 import/类型/调用，typecheck 与 build 零错。
- **INV-3 i18n 键平价**：`messages/zh-CN.json` 与 `en-US.json` 键集完全相同，每处 `t("key")` 可静态解析、无缺键。
- **INV-4 语义保真**：i18n 迁移不改任一 locale 下迁移前的文案语义；数据术语保持英文。
- **INV-5 零契约/内核改动**：全程不改同步 API 契约、不改 DB schema、不动调度/权限内核、不改根 pom modules。
- **INV-6 纯文档化（US4）**：US4 无任何运行期代码行为改动。

## 验证映射（FR/SC → 不变量）

| 需求 | 由不变量保障 |
|---|---|
| FR-001..004 / SC-001..002（举手台移除、布局完整、零悬空） | INV-1 + INV-2 |
| FR-005..009 / SC-003..004（i18n 完整、键平价、语义保真） | INV-3 + INV-4 |
| FR-010..011 / SC-005（alert 目录删除、构建通过、modules 不变） | INV-5 |
| FR-012..014 / SC-006（血缘缺口文档化、无代码改动） | INV-6 |
| FR-015 / SC-007（零新增能力、零契约/内核改动） | INV-5 |
