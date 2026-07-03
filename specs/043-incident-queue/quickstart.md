# Quickstart: Incident 域模型 + 监督席队列验证 (043)

端到端验证指南。契约细节见 [contracts/](contracts/)，字段语义见 [data-model.md](data-model.md)。

## 前置

```bash
# 后端（H2 零依赖即可；PG 路径也须各测一遍——方言坑）
cd backend && ./dev-install.sh
./mvnw -pl dataweave-api spring-boot:run -Dspring-boot.run.profiles=h2   # :8000
# 前端
cd frontend && pnpm dev                                                   # :4000
# 长命令一律 setsid 脱离（WSL2 硬规则，见 CLAUDE.md）
```

登录种子账号（`data.sql`：admin/admin，`{plain}` 编码）。浏览器验证按 playwright 门惯例：注入 `dw.auth.token` + `dw.auth.user`。

## 场景 1：故障即工单 + 去重（US1 / SC-001 / SC-002）

1. 造一个必失败任务（exit 1 脚本）并上线调度，或对既有任务实例直接触发失败路径。
2. 30s 内 `GET /api/incidents?projectId=1` 的 `active` 出现该卡（state=OPEN，signature=`T:{taskId}:EXIT_NONZERO`）。
3. 重复触发失败 ×4 → 仍 1 张卡，`occurrenceCount=5`，timeline 有 5 条 SIGNAL。
4. 浏览器：首开即 incidents tab 激活（SC-004），卡片可见。

## 场景 2：自动愈合 + 自动关闭（US1 / SC-003 / clarify Q1）

1. 修复脚本后重跑该任务至成功。
2. 30s 内工单转 RESOLVED（resolution_kind=AUTO_HEAL），队列移入「近 24h 已解决」区。
3. RESOLVED 期间再次触发同签名失败 → 同一张卡重开（OPEN、计数累加、timeline 记复发）。
4. 自动关闭验证（不等 7 天）：SQL 把 resolved_at 改为 8 天前 → 等一个 sweeper 周期（60s）→ state=CLOSED、active_key=NULL；再触发同签名失败 → **新卡**，且 `priorIncidentCount≥1`。

## 场景 3：分诊（US2 / SC-006）

1. 准备带血缘下游（neo4j 有 WRITES/READS 链）且下游 workflow 有 `sla_baseline` 基线（≥3 次成功历史）的任务，令其失败。
2. 卡片显示「影响 N 个下游」+ SLA 倒计时；`GET /api/incidents` 中 blastRadius/timeBudgetAt 非空。
3. 缺省态：无下游任务失败 → 「无下游影响」；关掉 neo4j 再触发 → blastRadius=null →「血缘不可用」。
4. 排序：构造两张卡（一张时间预算紧、一张仅严重度高）→ 时间预算紧者在前。

## 场景 4：处置闭环（US3 / SC-005）

1. 卡片点「重跑」→ 按 policy_rules 配置分流：
   - L1 场景：outcome=EXECUTED，卡片转处置中，timeline 有 ACTION 条目，`agent_action.incident_id` 已关联。
   - L2 场景（默认未配规则时 TOOL 写默认 L2——既有语义）：outcome=PENDING_APPROVAL，卡片出待审批角标。
2. 以 OWNER 在卡片内批准 → 动作执行、timeline 追加 APPROVAL；驳回路径同验。
3. 静默：填原因 → 卡片移出活跃区；历史筛选 SUPPRESSED 可见；恢复 → 回活跃区。静默期间触发失败 → 计数累加但不回队列。
4. 点击数验证：看到卡片 → 重跑完成 ≤3 次点击（SC-005）。

## 场景 5：归并风暴（SC-008）

1. 一个 10 节点 workflow，令上游首节点失败导致后续全失败 + 触发该 workflow SLA_BREACH。
2. `active` 新增卡片 ≤1（同 workflow_instance_id 归并），severity 升为 CRITICAL（SLA 信号并入），timeline 含全部信号。

## 回归门（收口必跑）

```bash
cd backend && ./mvnw -q -pl dataweave-master,dataweave-api compile     # 零错误
# 测试（setsid 脱离跑）：master incident 单测 + api 契约测试（WebTestClient+JwtTestSupport）
cd frontend && pnpm typecheck && pnpm test && pnpm i18n:lint            # 键集一致硬门
# playwright 浏览器门：主页=incidents、卡片渲染、动作分流、无 JS 异常
```

预期不变量：alerts/event-center 视图与端点行为零变化（FR-014）；既有 `store.test.ts`/`nav-groups.test.ts` 全绿（base tab 数量断言随 pinned 3 个同步更新属本期修改范围）。
