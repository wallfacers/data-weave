# Data Model: 调度与运行态总览日期筛选

**Feature**: 040-ops-summary-date-filter | **Date**: 2026-07-02

> 本 feature **不改数据库表**。下面记录涉及字段（只读）+ 后端方法签名变更。

## 1. 数据库表 `task_instance`（既有，不改 — schema.sql:517）

本 feature 使用的既有字段：

| 字段 | 类型 | 说明 | 用途 |
|------|------|------|------|
| `biz_date` | VARCHAR(32) | 业务日期 | **新增**筛选条件 |
| `run_mode` | VARCHAR(32) DEFAULT 'NORMAL' | 运行模式 | 既有过滤（NORMAL only，不变） |
| `state` | VARCHAR(32) | NOT_RUN/WAITING/.../FAILED | 既有聚合分类（不变） |
| `project_id` | BIGINT | 项目隔离 | 既有过滤（不变） |

**无新增列、无 schema_version 变更。**

## 2. 后端 Repository 扩展 — `TaskInstanceRepository.java`

新增一个查询方法（Spring Data JDBC 衍生方法，零 SQL 手写）：

```java
/** 040 日期筛选：按项目 + runMode + 业务日期过滤实例。 */
List<TaskInstance> findByProjectIdAndRunModeAndBizDate(Long projectId, String runMode, String bizDate);
```

## 3. 后端方法签名变更 — `OpsService.java`

### `summary` 方法

```java
// before
public DashboardSummary summary(Long projectId)

// after
public DashboardSummary summary(Long projectId, String bizDate)
```

### `instances` 方法（内部调用 `summary` 时）

`bizDate` 非 null 时调用 `findByProjectIdAndRunModeAndBizDate`，为 null 时调用原有 `findByProjectIdAndRunMode`。伪代码：

```
if (bizDate != null) → repository.findByProjectIdAndRunModeAndBizDate(projectId, "NORMAL", bizDate)
else                → repository.findByProjectIdAndRunMode(projectId, "NORMAL")        // 不变
```

## 4. 后端 Controller 变更 — `OpsController.java`

`/summary` 端点增加可选参数：

```java
@GetMapping("/summary")
public ApiResponse<OpsService.DashboardSummary> summary(
        @RequestParam(required = false) Long projectId,
        @RequestParam(required = false) String bizDate) {  // ← 新增
    return ApiResponse.ok(opsService.summary(resolveProjectId(projectId), bizDate));
}
```

## 5. 前端类型 — `lib/types.ts`

`DashboardSummary` 接口不变（字段无变化，仅值按日期过滤）。

## 6. 前端 i18n 变更

| key | zh-CN | en-US |
|-----|-------|-------|
| `topTotal` | ~~今日总数~~ → **总数** | ~~Today Total~~ → **Total** |

其余 key 不变。

## 7. 前端组件变更 — `top-strip.tsx`

新增：
- `bizDate` state（`string`，默认今天 `yyyy-MM-dd`）
- DatePicker 组件（复用 `components/ui/date-picker.tsx`）
- URL 拼接：`/api/ops/summary?projectId=${projectId}&bizDate=${bizDate}`

## 验证规则

- `bizDate` 传入无数据日期 → 统计全 0，不报错。
- `bizDate` 不传 → 行为与当前一致（全量统计），向后兼容。
- `bizDate` 传入非法格式 → Spring MVC 参数绑定失败，返回 400。
