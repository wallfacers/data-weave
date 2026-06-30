# Quickstart: 029 资产 / 指标市场前端收口

> 隔离 worktree：`/home/wallfacers/project/dw-029-asset-frontend`（分支 `029-asset-frontend`）。**所有 029 操作在此 worktree 内进行**,勿回 main 工作副本（外部 agent 占用）。

## 起栈（零外部依赖,H2 内存库）

```bash
# 后端（隔离 worktree 内,h2 profile 免 Docker）
cd /home/wallfacers/project/dw-029-asset-frontend/backend
./dev-install.sh
./mvnw -pl dataweave-api spring-boot:run -Dspring-boot.run.profiles=h2   # :8000

# 前端
cd /home/wallfacers/project/dw-029-asset-frontend/frontend
pnpm install && pnpm dev                                                 # :4000
```

> WSL2 长跑（后端起栈/编译）须 `setsid` 脱离 + 单次秒回轮询（见 CLAUDE.md 硬规则）。

## 浏览器手验（admin/admin → JWT 注入）

1. `http://localhost:4000`,登录 admin/admin（拿 JWT 注 `localStorage dw.auth.token`）。
2. 深链开视图：`/?open=catalog`（资产目录）、`/?open=marketplace`（指标市场）。

## 闭环验收脚本（对应 SC-001~006）

**资产全生命周期（US1 / SC-001）**
1. 目录头「编目资产」→ 选数据源、填 qualifiedName + 元数据 → 提交 → 列表出现（或「待审批」）。
2. 重复同 qualifiedName 提交 → 明确「资产已存在」提示,无重复。
3. 详情「编辑」→ 只改描述 → 提交 → 仅描述变,其它不变。
4. 编辑填 `lineageTableRef` → 对账 → 状态 ACTIVE；清空 ref → 对账 → STALE。
5. 详情「下线」→ 确认 → 状态 RETIRED。

**指标上架/下架（US2 / SC-002）**
6. 市场头「上架指标」→ 从定义池选一个 → 提交 → 进市场列表。
7. 同指标重复上架 → 幂等,不重复。
8. 详情「下架」→ 确认 → DELISTED。
9. 复用且构造成环 → 「会形成循环依赖」专门提示（非通用失败）。

**订阅（US3 / SC-003）**
10. 资产详情「订阅」→ EXECUTED/待审批。
11. 目录头「我的订阅」→ 聚合清单见订阅 → 退订 → 清单移除。

**检索体验（US4 / SC-004）**
12. 点 owner/tag/status/certification 分面 → 结果收窄、分面高亮；再点取消。
13. 结果 >20 → 翻页正常；触达上限显示「结果已截断」。
14. 设质量分数下限 → 透传生效；控件旁见「质量数据来自 022、当前可能为空」静态声明。

**三态如实（SC-005）**：上述任一写操作命中审批闸门时,提示「待审批」且条目未乐观出现（不伪装成功）。

## 自动化测试

```bash
cd /home/wallfacers/project/dw-029-asset-frontend/frontend
pnpm typecheck        # 零错误
pnpm test             # vitest：catalog-api 各方法 URL/method/body + Dialog 交互 + 三态/防环
pnpm design:lint      # 设计令牌
# i18n key 一致性（两 bundle 同集）CI 校验
```

## 完成定义（Definition of Done）
- 14 项手验闭环全过 + vitest/typecheck/design:lint/i18n 全绿。
- 零后端/schema 改动（`git diff --stat` 仅 `frontend/` + `specs/029*`）。
- 三态如实、闸门零旁路、i18n 两 bundle 同 key 集。
