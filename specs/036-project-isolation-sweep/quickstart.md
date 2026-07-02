# Quickstart: 项目级数据隔离全盘收口

## 验证场景

### 地基验证（阻塞，先通过）

```bash
# 1) TenantContext projectId 贯通
cd backend
./mvnw -pl dataweave-api test -Dtest=TenantContextTest

# 2) ProjectScope 成员校验
./mvnw -pl dataweave-master test -Dtest=ProjectScopeTest

# 3) JwtAuthFilter 解析 X-Project-Id / ?projectId=
./mvnw -pl dataweave-api test -Dtest=JwtAuthFilterTest
```

**预期**：`TenantContext.projectId()` 在请求周期内正确设入/清除；非成员 projectId → `project.forbidden`(403)；缺失 → `project.required`。

### Workstream A: 运维/运行态隔离

```bash
# 后端：实例列表按 projectId 过滤
./mvnw -pl dataweave-master test -Dtest=OpsServiceTest

# 前端（浏览器）：切项目 A → ops 三 panel 只显示 A 的实例 → 切 B → 只见 B
# 切换 bizDate → 计数与表格随日期收敛
cd frontend && pnpm dev
```

**预期**：`GET /api/ops/instances?projectId=A&bizDate=...` 只返回 A 的行；切到 B 返回 B；日期空态显示明确文案。

### Workstream B: 指标/血缘/时效隔离

```bash
# 后端
./mvnw -pl dataweave-master test -Dtest=MetricServiceTest
./mvnw -pl dataweave-master test -Dtest=LineageServiceTest
```

**预期**：`LineageService.lineageOf()` 从 `TenantContext.projectId()` 取 projectId 而非常量 1L；指标按 (projectId, bizDate) 过滤。

### Workstream C: Schema 迁移 + 告警/质量

```bash
# PG
docker compose up -d
./mvnw -pl dataweave-api test -Dtest='*SchemaMigration*' -Dspring-boot.run.profiles=default

# H2
./mvnw -pl dataweave-api test -Dtest='*SchemaMigration*' -Dspring-boot.run.profiles=h2
```

**预期**：8 表（alert_rule/event/channel/route + quality_rule/check_run + cron_fire + sla_baseline）均有 `project_id` 列 + 索引；存量按"该租户最早项目"回填无孤儿；`schema_version` 升版三处恒等（库内/文件头/项目版本）。

### Workstream D: 角色/菜单隔离

```bash
# 后端
./mvnw -pl dataweave-master test -Dtest='*Role*'
```

**前端验证**（浏览器）：
- VIEWER 登录 → 导航只显示只读视图入口 → 构造写请求 → `project.role.forbidden`
- 切到 EDITOR 角色的项目 → 编辑入口出现 → 切回 VIEWER 项目 → 入口消失

## 前提条件

- PostgreSQL（`docker compose up -d`）或 H2
- backend: `./dev-install.sh` 已执行
- frontend: `pnpm install && pnpm dev`
- 测试数据：至少两个项目 A/B，各含实例/指标/血缘数据，用户在两项目中角色不同（A=EDITOR, B=VIEWER）
