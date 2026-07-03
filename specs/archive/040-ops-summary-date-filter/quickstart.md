# Quickstart Validation: 调度与运行态总览日期筛选

**Feature**: 040-ops-summary-date-filter | **Date**: 2026-07-02

## Prerequisites

- 后端运行中（`:8000`），数据库有任务实例数据（含不同 `biz_date`）
- 前端运行中（`:4000`）
- 有可用的 JWT token（参考 MEMORY `backend-fullstack-http-test-jwt`）

## Validation Scenarios

### 1. API — 不传 bizDate（向后兼容）

```bash
curl -s -H "Authorization: Bearer $DW_TOKEN" \
  "http://localhost:8000/api/ops/summary?projectId=1" | jq '.data.total'
```

**Expected**: 返回全量任务实例总数，与改动前一致。

### 2. API — 传 bizDate 过滤

```bash
curl -s -H "Authorization: Bearer $DW_TOKEN" \
  "http://localhost:8000/api/ops/summary?projectId=1&bizDate=2026-07-02" | jq '.data'
```

**Expected**: 返回 2026-07-02 当天的统计数据。验证 `total` = `success + failed + running`。

### 3. API — 无数据日期

```bash
curl -s -H "Authorization: Bearer $DW_TOKEN" \
  "http://localhost:8000/api/ops/summary?projectId=1&bizDate=2020-01-01" | jq '.data.total'
```

**Expected**: `total = 0`，`failedInstances = []`，不报错。

### 4. 浏览器 — DatePicker 切换

1. 打开运维中心（`http://localhost:4000` → 运维监控 → 调度与运行态总览）
2. 确认顶条 DatePicker 默认选中今天
3. 切换到一个有数据的日期
4. 确认 4 个统计数字（总数/运行中/成功/失败）随之变化
5. 确认 SLA 风险数字不变

### 5. 浏览器 — 标签验证

1. 查看顶条第一个统计项标签
2. **Expected**: 显示"总数"（中文）或 "Total"（英文），不含"今日"

### 6. 浏览器 — Typecheck

```bash
cd frontend && pnpm typecheck
```

**Expected**: 零错误。

### 7. H2 环境

```bash
cd backend && ./mvnw -pl dataweave-api -am spring-boot:run -Dspring-boot.run.profiles=h2
```

验证 API 行为与 PG 一致（`biz_date` 为 VARCHAR 列，WHERE 条件在两个方言下行为相同）。
