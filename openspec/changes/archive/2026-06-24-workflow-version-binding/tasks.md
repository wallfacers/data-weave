## 0. 前置协调（与 workflow-dependency-modes 对齐，先做）

- [x] 0.1 与 workflow-dependency-modes 在途负责人对齐落地顺序：本 change 的 §2「trigger 物化重写」作为地基先落，或与其 §4 trigger 改造合并提交；确认「trigger 只认快照」为共享契约
- [x] 0.2 确认快照 JSON 演进方式：本 change 依赖 `SnapshotNode.taskVersion`（已存在）成为规定性；dependency-modes 加 `SnapshotEdge.strength`——二者纯叠加，约定序列化/反序列化兼容彼此字段

## 1. Schema 与实体（env 维度）

- [x] 1.1 `schema.sql`（H2/PG 共用）`workflow_instance` 加 `env VARCHAR(8) DEFAULT 'PROD'`（实现改为**可空**：NULL≡PROD，不强制所有触发路径 set，兼容并发 agent 不设 env 的 trigger 路径——见 design 决议④）
- [x] 1.2 `schema.sql` `task_instance` 加 `env VARCHAR(8) DEFAULT 'PROD'`（同 1.1，可空）
- [x] 1.3 实体 `WorkflowInstance` / `TaskInstance` 加 `env` 字段 + getter/setter
- [x] 1.4 `data.sql` 现有实例 seed（如有）不显式写 `env`，走默认 PROD（兼容）——3 处实例 insert 均无 env 列，加载 data.sql 的测试全过即实证
- [x] 1.5 `./mvnw -q -pl dataweave-api -am compile`（JDK25）零错误；H2 经测试加载 schema.sql 建表通过；env 用标准 `VARCHAR(8) DEFAULT 'PROD'`，PG 兼容（共享 DDL 约定）

## 2. 触发器物化重写（核心：以快照为唯一真相）

- [x] 2.1 `WorkflowDefVersionRepository` 增「按 `(workflowId, versionNo)` 取 `workflow_def_version`」查询，供 trigger 读快照
- [x] 2.2 快照反序列化复用 `WorkflowService` 的 `Snapshot`/`SnapshotNode`/`SnapshotEdge` 记录类（或抽到共享位置），`trigger` 可读 `dag_snapshot_json`
- [x] 2.3 `WorkflowTriggerService.trigger` 物化源由 live `nodeRepository.findByWorkflowIdAndDeleted` + live `taskDefRepository...currentVersionNo` 改为解析快照：拓扑、`task_id`、`task_version_no` 全取快照；VIRTUAL 判定按快照 `node_type`
- [x] 2.4 `nodeKey → workflow_node.id` 映射：按 `(workflowId, nodeKey)` 查 live node 取物理主键填 `task_instance.workflow_node_id`；live 已删该 nodeKey 时跳过物化并告警（不静默丢）
- [x] 2.5 `workflow_instance.workflow_version_no` 设为所物化的快照版本号（名副其实）
- [x] 2.6 无快照回退：`current_version_no=0`/无 `workflow_def_version` 时回退现状 live 物化（兼容未发布工作流）
- [x] 2.7 死锁防御不变量回归确认：物化仍事务内只落库、状态全 WAITING、认领走既有 SKIP LOCKED + CAS，不改就绪门

## 3. env 落值（触发入口判定）

- [x] 3.1 `trigger`（CRON/正式手动 NORMAL）落 `env=PROD`；`triggerManualTaskRun` 落 PROD
- [x] 3.2 `triggerTestRun`（含 `run_mode=TEST`）落 `env=DEV`
- [x] 3.3 各触发入口（`SchedulerKernel`/`CronScheduler` cron 路径、agent、手动 controller）确认透传/默认 env 正确

## 4. 漂移检测（读侧）

- [x] 4.1 `WorkflowService.computeDrift(workflowId)`：取当前快照节点 taskId 集合，一次 IN 查询各 task `current_version_no`，比对快照 `taskVersion`；并入 `has_draft_change` 判 DAG 草稿漂移
- [x] 4.2 返回结构 `{drifted, driftedNodes:[{nodeKey,pinned,latest}], dagDraft}`；列表页只需布尔，详情页返回明细
- [x] 4.3 工作流详情/列表 DTO 暴露漂移字段（列表 boolean、详情明细）；不落库，每次读时算

## 5. 重新晋级（复用发布端点）

- [x] 5.1 「重新晋级到最新」复用 `WorkflowService.publish`（已存在）：从各任务最新 `current_version_no` + live DAG 重建 `dag_snapshot_json`、校验无环、`current_version_no++`、清 `has_draft_change`——前端「重新晋级」按钮直接调 `POST /{id}/publish`
- [x] 5.2 重新晋级为 UI 编辑类操作，沿用 publish 的非闸门直执行（与 saveDag/update/offline 一致）；审计由新建 `workflow_def_version` 行天然留痕（修正原设计：不强加闸门，避免与编辑类操作约定不一致）
- [x] 5.3 确认重新晋级后漂移消除（快照版本对齐任务最新发布版）——后端单测 §6.6 `rePromote_rebuildsSnapshot_clearsDrift_triggerUsesNewVersion` 覆盖，通过

## 6. 后端测试

- [x] 6.1 `WorkflowTriggerService` 单测：从快照物化（节点 A=v3、B=v2 钉死）；`workflow_version_no` 等于快照版本号
- [x] 6.2 单测：发布后 live DAG 加节点不影响周期运行物化；快照 nodeKey 在 live 已删时跳过 + 告警
- [x] 6.3 单测：任务发新版（v3→v4）后未重新晋级，触发仍跑 v3
- [x] 6.4 单测：无快照工作流回退 live 物化（兼容）
- [x] 6.5 单测：`computeDrift` —— 任务版本漂移 true、DAG 草稿漂移 true、无更新 false、明细正确
- [x] 6.6 单测：重新晋级重建快照采纳最新版、漂移消除、经闸门留痕
- [x] 6.7 单测：`env` 落值 —— CRON/正式手动 PROD、试跑 DEV、历史实例默认 PROD
- [x] 6.8 通过：WorkflowVersionBindingTest 8/8；master 全量 178/178（含 WorkflowStateService、调度策略，无回归）；api 回归 20/20（KernelSchedulingTest 6 死锁防御、ManualRunTrigger 7、WorkflowService 7）

## 7. 前端（漂移 badge + 重新晋级 + i18n）

- [x] 7.1 `lib/types.ts`：`DriftNode{nodeKey,pinned,latest}` + `DriftResult{drifted,dagDraft,driftedNodes}`，与后端 record 对应
- [x] 7.2 `workflow-canvas-view.tsx`：online && drifted 时工具栏右簇展示漂移 badge（`title` tooltip 逐节点 `nodeKey: vP→vL` + DAG 草稿提示）；`loadDrift` 拉 `GET /{id}/drift`，初始加载 + publish 后刷新
- [x] 7.3 「重新晋级」按钮（RocketIcon）调 publish 路径（D4 修正：复用 publish，UI 编辑类非闸门，无 PENDING_APPROVAL；handler 按非零 code toast `j.message`，非只看 code===0）；dirty 时禁用并提示先存草稿
- [x] 7.4 `messages/{zh-CN,en-US}.json` 新增 `driftBadge`/`driftDagHint`/`rePromote`/`rePromoteHint`（workflowCanvas 命名空间），两 bundle 等集，无硬编码回退
- [x] 7.5 `pnpm typecheck` 零错误（含顺带补齐并发 agent 的 WorkflowConfigPanel 调用点——其已自修）；i18n 对称检查 zh-only/en-only 均空

## 8. 验证与收尾

- [x] 8.1 浏览器验证门（playwright，headless chromium，前端 4000 → 重启后的 8000/H2 当前代码）**真实通过**：深链 `?open=workflow-canvas&workflowId=1` 开画布 → 工具栏显示「漂移 1」琥珀 badge + 「重新晋级」火箭按钮（i18n 正确渲染）→ 点重新晋级 → badge 消失（漂移消除）→ **console 错误 0**（无 missing i18n key）。RESULT pass=true，截图实证 gate-1-drift.png（验证后清理 tmp/）
- [x] 8.2 隔离端口 8001（H2，setsid 脱离）起本 change 后端做 HTTP E2E 冒烟，admin/admin 取 JWT，全闭环通过：①/drift=false → ②发布任务2→v2 → ③/drift drifted=true `n1 pinned=1 latest=2` → ④run 无「回退 live」告警、从快照物化 → ⑤重新晋级 publish code=0（**no_change 守卫被 drifted 放行=`&&!computeDrift().drifted()` 修复线上实证**）→ ⑥/drift=false。**并抓到单测测不到的真 bug**（见 8.6）
- [x] 8.6 **浏览器/HTTP 门捕获的回归**：seed `data.sql` 工作流1 的 `dag_snapshot_json` 是旧格式（`"key"`/`"taskVersion"`，edges 为 `[["n1","n2"]]` 数组套数组），与新 `WorkflowDagSnapshot` record（`nodeKey`/`taskVersionNo`，edges 为对象）不兼容 → 解析抛 `Cannot deserialize Edge from Array` → trigger 静默回退 live 物化（版本钉死对全 seed 失效）+ computeDrift 吞异常恒返 false。单测自带 canonical 种子故测不出。已修工作流1 快照为新格式（工作流3 此前已由 dependency-modes 迁移、工作流2 空 nodes/edges 无害）
- [x] 8.3 与 workflow-dependency-modes 排序落定：D1 trigger 物化先落地铺地基，其 Slice（strength/earliest_biz_date）叠加；共存零冲突——无 git 冲突标记 + 206 后端测试全绿（master 178 + api 26）实证。`WorkflowDagSnapshot.Edge` 共享 record，version-binding 用 taskVersionNo / dependency-modes 用 strength，纯字段叠加。（其弱依赖后端 Slice 若后续落地，复测在那侧）
- [x] 8.4 design Open Questions 四问按实现落定写入 design.md「决议」节（合并 vs 排序=排序、漂移粒度=布尔+明细分层、diff 预览=MVP 不做、env=两处都落可空）
- [x] 8.5 `openspec status --change workflow-version-binding` 全部 done（4/4 artifacts complete，全 tasks 勾完），就绪 `/opsx:archive`（待用户确认归档；注：合并 main 上 api 测试模块当前因 dependency-modes 在途的 `DependencyDto`/测试版本错位不编译——非本 change，待其收口）
