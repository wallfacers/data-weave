# Quickstart 验证: 周期/手动任务流列表字段增强

**Feature**: 039-workflow-list-fields | **Date**: 2026-07-02

> 端到端验证清单（对应 spec 的 US1–US4 与 FR-001~011）。前置：后端 H2 起、前端起、已有 ONLINE 的 CRON/MANUAL 工作流（data.sql seed 或手动建，覆盖 priority 0–2 与 3–9）。

## 前置

```bash
# 后端（H2，零外部依赖）
cd backend && setsid bash -c './mvnw -pl dataweave-api spring-boot:run -Dspring-boot.run.profiles=h2 >api.log 2>&1' </dev/null >/dev/null 2>&1 & disown
# 健康检查
curl -s http://localhost:8000/api/health
# 前端
cd frontend && pnpm dev   # http://localhost:4000
```

登录（注入 `dw.auth.token` / `dw.auth.user`，见 MEMORY `playwright-browser-gate-setup`）后开「运营中心」。

## 验证场景

### V1 周期卡片：下次触发时间（相对）+ 优先级 + 描述副标题（US1/US2/US3）
1. 切到「周期任务流」Tab。
2. ✅ 出现「下次触发时间」列，值为相对时间（如"3 小时后"）；未回填的流显示 `—`。
3. ✅ 出现「优先级」列：priority 0–2 显示数字 + 橙色高优徽标，3–9 仅数字。
4. ✅ 「任务流名称」下方一行描述副标题；无描述的流不预留空行。
5. ✅ 列宽无横向滚动（widthPct 和=100）。

### V2 手动卡片：优先级 + 描述副标题（US2/US3）
1. 切到「手动任务流」Tab。
2. ✅ 出现「优先级」列（同 V1 规则）；✅ 描述副标题。
3. ✅ 无「下次触发时间」列（手动流无 cron）。

### V3 优先级筛选（US4 / FR-010/011）
1. 筛选栏选「优先级 → 高优」。
2. ✅ 列表仅显示 priority 0–2 的流（URL 含 `priorityTier=high`）。
3. 切「普通」→ 仅 3–9；切回「全部」→ 恢复。

### V4 优先级列排序（US4 / FR-010/011）
1. 点「优先级」列头 → 降序。
2. ✅ server 端按 priority 重排（URL 含 `sort=priority:desc`）；NULL 行置末（NULLS LAST）。
3. 再点 → 升序；再点 → 清除排序（回默认按 id）。

### V5 i18n（FR-005/008/009）
1. 切英文。
2. ✅ 新列头、相对时间（"in 3 hours" / "expired 2m"）、高优徽标（"High"）、筛选器标签均英文，无 console 缺 key 报错。

### V6 后端契约（curl 直连，绕过 Next 代理）
```bash
curl -s -H "Authorization: Bearer $DW_TOKEN" \
  "http://localhost:8000/api/ops/periodic-workflows?projectId=1&priorityTier=high&sort=priority:desc" \
  | jq '.data.items[] | {name,priority,nextTriggerTime,recentTriggerResult}'
```
✅ items 含 `nextTriggerTime`；`priorityTier=high` 仅返回 priority 0–2；`sort=priority:desc` 按 priority 降序。

## 自动化测试对应

- **后端**：WebTestClient（带 JWT）断言 `nextTriggerTime` 投影 + `priorityTier` 过滤 + `sort` 排序（含 `NULLS LAST`）。须用独立 H2 库名（见 MEMORY `h2-shared-mem-db-test-pollution`）。
- **前端**：vitest 覆盖 `relative-time.ts` 三态（未来/已过期/临近 + 单位选择）+ `data-table.ts` `toQueryParams` sort 序列化。

## 不在本 quickstart（后续 / plan 外）
- 负责人名字、类目路径、节点任务数（C 类高成本 join，spec 已排除）。
- 窄屏响应式列隐藏（无框架，后续视反馈）。
