# Data Model: 资产目录页面规范化重设计

**Created**: 2026-07-05 | **Plan**: [plan.md](./plan.md)

## 概述

本特性为纯前端改造，**不新增**后端实体或数据库表。所有实体沿用 023-asset-catalog-metric-marketplace 定义的后端模型。以下仅记录前端消费的类型定义（TypeScript），与 `lib/catalog-api.ts` 中既有类型完全一致。

## 实体

### AssetSummary（资产列表项 / 卡片摘要）

搜索列表返回的资产摘要，用于卡片网格渲染。

| 字段 | 类型 | 必填 | 用途 |
|------|------|------|------|
| `id` | `number` | ✅ | 资产唯一标识 |
| `qualifiedName` | `string` | ✅ | 限定名（数据源内唯一） |
| `name` | `string` | ❌ | 显示名，可能为空回退到 qualifiedName |
| `description` | `string` | ❌ | 描述文本，卡片展开时显示 |
| `sensitivity` | `"PUBLIC" \| "INTERNAL" \| "CONFIDENTIAL" \| "PII"` | ✅ | 敏感度级别 → Badge variant（FR-011） |
| `status` | `"ACTIVE" \| "STALE" \| "RETIRED"` | ✅ | 资产状态 → Badge variant（FR-010） |
| `ownerId` | `string` | ❌ | 负责人 ID |
| `stewardId` | `string` | ❌ | 数据管家 ID |
| `tags` | `string[]` | ❌ | 标签列表 |
| `lastUpdatedAt` | `string` (ISO 8601) | ❌ | 最近更新日期 |
| `datasourceId` | `number` | ✅ | 归属数据源 ID |

### AssetDetail（资产详情）

选中资产后的完整详情，用于卡片内联展开区。

| 字段 | 类型 | 必填 | 用途 |
|------|------|------|------|
| 继承 `AssetSummary` 所有字段 | | | |
| `lineageTableRef` | `string` | ❌ | 血缘锚点表引用 |
| `stewardId` | `string` | ❌ | 数据管家 ID |

### AssetFacets（分面计数）

服务端返回的聚合计数，用于 toolbar segmented 控件渲染。

| 字段 | 类型 | 用途 |
|------|------|------|
| `sensitivity` | `Record<string, number>` | 敏感度维度计数（含 PUBLIC/INTERNAL/CONFIDENTIAL/PII） |
| `owner` | `Record<string, number>` | 负责人维度计数 |
| `tag` | `Record<string, number>` | 标签维度计数 |
| `status` | `Record<string, number>` | 状态维度计数（仅只读展示，不可筛选） |

### AssetSubscription（资产订阅）

用户订阅记录，用于详情区订阅态判定和"我的订阅"面板。

| 字段 | 类型 | 用途 |
|------|------|------|
| `subscriptionId` | `number` | 订阅记录 ID |
| `assetId` | `number` | 关联资产 ID |
| `subscribedAt` | `string` (ISO 8601) | 订阅时间 |

## 状态与敏感度 → Badge 映射

| 值 | Badge variant | 说明 |
|----|--------------|------|
| `ACTIVE` | `success` | 活跃（绿色） |
| `STALE` | `warning` | 待对账（琥珀色） |
| `RETIRED` | `muted` | 退役（灰色） |
| `PUBLIC` | `secondary` | 公开（灰色） |
| `INTERNAL` | `default` | 内部（默认） |
| `CONFIDENTIAL` | `warning` | 机密（琥珀色） |
| `PII` | `destructive` | 个人隐私（红色） |

## 无新增实体

本特性不引入新的数据库表、不修改既有 schema。所有数据经现有 REST 端点流转。
