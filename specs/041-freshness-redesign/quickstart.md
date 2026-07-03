# Quickstart: 数据新鲜度页面重新设计

**Feature**: 041-freshness-redesign
**Date**: 2026-07-03

## Prerequisites

- PostgreSQL + Redis（`docker compose up -d`）
- JDK 25（非交互 shell 自动 symlink）
- Node.js（前端 dev）

## Backend Setup

```bash
cd backend

# 1. 启动依赖
docker compose up -d

# 2. 构建全部模块（schema 自动初始化）
./dev-install.sh

# 3. 启动 API（含 H2 模式可跳过 docker）
./mvnw -pl dataweave-api spring-boot:run -Dspring-boot.run.profiles=h2
```

Schema 自动升级到 `0.6.3`（新增 `freshness_daily_snapshot` + `freshness_task_daily` 表）。

## Frontend Setup

```bash
cd frontend
pnpm install
pnpm dev
```

打开 `http://localhost:4000`，登录后进入运维中心 → 数据新鲜度标签。

## Verification

### 1. 概览区
- 页面顶部显示 4 个横排统计卡片（新鲜/偏旧/陈旧/从未成功）
- 下方显示数据健康度进度条 + 分布文字
- 首次使用（无快照）不显示日环比箭头

### 2. 增强表格
- 表格包含 7 列，包含新增的"调度周期"和"7天趋势"
- 火花图自适应数据点数（1-7 个点），当天点实心、历史点空心
- 时效 Badge 可点击联动筛选

### 3. 行操作
- 行末 5 个图标按钮，hover 显示 tooltip
- 重跑弹出确认弹窗
- 订阅告警为 toggle

### 4. 快照数据（需要先灌测试数据或等次日 02:00）

```sql
-- 手动插入测试快照（模拟 7 天数据）
INSERT INTO freshness_task_daily (tenant_id, project_id, task_id, snapshot_date, tier, age_hours)
VALUES
  (1, 1, 10, '2026-07-03', 'FRESH', 2),
  (1, 1, 10, '2026-07-02', 'FRESH', 5),
  (1, 1, 10, '2026-07-01', 'AGING', 10),
  (1, 1, 10, '2026-06-30', 'AGING', 16),
  (1, 1, 10, '2026-06-29', 'STALE', 30)
ON CONFLICT DO NOTHING;

INSERT INTO freshness_daily_snapshot (tenant_id, project_id, snapshot_date, total_tasks, fresh_count, aging_count, stale_count, never_count)
VALUES
  (1, 1, '2026-07-03', 178, 128, 35, 12, 3),
  (1, 1, '2026-07-02', 175, 120, 38, 14, 3)
ON CONFLICT DO NOTHING;
```

刷新页面 → 火花图显示 5 个数据点的折线，日环比出现在概览卡片下方。
