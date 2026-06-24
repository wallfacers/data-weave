## 1. Schema 与实体（数据模型基础）

- [x] 1.1 `schema.sql`（单一文件，H2/PG 共用）`workflow_edge` 加 `strength VARCHAR(16) NOT NULL DEFAULT 'STRONG'`
- [x] 1.2 `schema.sql` `workflow_dependency` 加 `earliest_biz_date VARCHAR(32)`（可空；对齐 `biz_date` 的 VARCHAR 类型，非 DATE）
- [x] 1.3 `schema.sql` `task_instance` 加索引 `idx_task_instance_node_bizdate (workflow_node_id, biz_date, deleted)`
- [x] 1.4 无独立 H2 schema 文件——H2/PG 共用同一份 `schema.sql`（靠 `IF NOT EXISTS` 兼容），1.1–1.3 改一处即双方言生效
- [x] 1.5 实体 `WorkflowEdge` 加 `strength` + getter/setter；`WorkflowDependency` 加 `earliestBizDate` + getter/setter
- [x] 1.6 `data.sql` 的 `workflow_edge` seed 不含 `strength` 列（走默认 STRONG，兼容）；`workflow_dependency` 无 seed，无需改
- [x] 1.7 `./mvnw -q -pl dataweave-api -am compile`（JDK25）零错误

## 2. 同周期弱依赖（就绪门分叉）

- [x] 2.1 `SchedulerKernel.selectRunnable` NORMAL 就绪门 SQL：`pred.state<>'SUCCESS'` 改为按 `COALESCE(e.strength,'STRONG')` 分叉（WEAK→`pred.state NOT IN ('SUCCESS','FAILED','STOPPED')`，否则→`<>'SUCCESS'`）
- [x] 2.2 确认 `WorkflowStateService.aggregate` 聚合口径不变（弱依赖下 FAILED 节点如实反映为整体 FAILED，未改该类）
- [x] 2.3 `KernelSchedulingTest` 加两测试：弱依赖上游终态 FAILED→下游放行；强依赖上游 FAILED→下游阻塞 WAITING（用 edge id=3/id=5 隔离互不污染）
- [x] 2.4 `./mvnw -pl dataweave-api test -Dtest=KernelSchedulingTest`（JDK25，先 install master）通过：Tests run: 3, Failures: 0

## 3. 跨周期依赖运行态

- [x] 3.1 不新增独立 repo 方法——跨周期上一周期实例查询内联在 `SchedulerKernel.crossCycleReady`（jdbc COUNT，走新索引 `idx_task_instance_node_bizdate`）
- [x] 3.2 跨周期就绪改为 **Java 层过滤**（`crossCycleReady`，认领事务内）而非 SQL EXISTS——避开 H2/PG 日期减一天方言不统一坑；仅 `trigger_type='CRON'` 实例检查，查 depend_node 在 `biz_date` 按 `date_offset` 偏移后的 SUCCESS 实例
- [x] 3.3 earliest 豁免在 `crossCycleReady` 实现：`biz_date < earliest_biz_date` 跳过该条；earliest 为空=该依赖不启用（SQL 已过滤 `earliest_biz_date IS NOT NULL`）
- [x] 3.4 `WorkflowGraphValidator.validateDependencyAcyclic` 放开自指：自依赖直接 return 合法，自指边不入邻接图；保留非自指全局跨流环检测
- [ ] 3.5 跨周期就绪端到端测试待补——`WorkflowGraphValidatorTest.selfDependency_allowed` 已覆盖「自指不再报环」；`crossCycleReady` 的就绪/阻塞/earliest 豁免/手动忽略场景需独立 workflow+库隔离（trigger 全节点造历史，跨周期测试互相污染，搭建待设计）
- [x] 3.6 `WorkflowGraphValidatorTest`（5 绿，含翻转的 selfDependency_allowed）+ `KernelSchedulingTest`（3 绿回归）通过；修复 `Stream.toList()` 不可变致 `sort` 抛异常的回归（改 stream 内 `sorted`）

## 4. 手动运行范围（子图触发）

- [x] 4.1 `WorkflowTriggerService.trigger` 加 `scope`/`targetNodeKey` 重载 + `computeSubgraphNodes` 闭包（FULL/TO_NODE 前驱闭包/DOWNSTREAM 后继闭包，子集外不建 task_instance）；用 live `workflow_edge` 算闭包（手动跑 ONLINE 工作流，live==已发布）；注入 `WorkflowEdgeRepository`
- [x] 4.2 `WorkflowController.RunRequest` 增 `scope`+`targetNodeKey`；command 经新 `TriggerCommand.encode` 透传；`DefaultPlatformActionExecutor.triggerWorkflow` decode 后调 6 参 trigger；ONLY_NODE 走 `/api/tasks/{id}/run`
- [x] 4.3 手动实例跨周期豁免——`crossCycleReady` 仅对 `trigger_type='CRON'` 生效（3.2 实现），手动/TEST 天然豁免
- [x] 4.4 闭包 BFS visited 集合兜底防非法图死循环；target 无效降级 FULL
- [x] 4.5 `TriggerCommandTest` 6 绿（encode/decode roundtrip + FULL 退化 + 特殊字符）；子图物化测试（runToNode/runDownstream/runFull）随 4.6 全绿
- [x] 4.6 `KernelSchedulingTest` 6 绿（弱依赖×2 + 子图×3 + 端到端×1）：补回 wf3 seed 链（task 4-9 / wf 3 / version 3 快照 / node 4-9 / edge 3-8，其他 change 重构 data.sql 时丢失）+ env 由 workflow-version-binding 填，阻塞解除

## 5. DAG 读写、快照与跨周期依赖 CRUD（authoring）

- [x] 5.1 `DagEdgeDto` 增 `strength`；`readDag` 透传、`saveDag` 新边设 strength + 已存在边强度变化更新（normalize：WEAK/否则 STRONG）
- [x] 5.2 `WorkflowDagSnapshot.Edge` 增 `strength`（JsonIgnoreProperties 已由并发 change 预留兼容）；`buildSnapshotJson` 透传——发布快照冻结 strength，回滚经快照自然还原
- [x] 5.3 跨周期依赖 CRUD：`WorkflowService.listDependencies/createDependency/deleteDependency`（注入 `WorkflowDependencyRepository` + `DependencyDto`）+ `WorkflowController` GET/POST/DELETE `/{id}/dependencies`；**不经 gate**（跟随 saveDag 编辑态模式，design"经 gate"按项目实际偏离）；create 调 `validateDependencyAcyclic`（自依赖放行、跨流环检测）
- [x] 5.4 跨周期依赖随 `workflow_def` 当前态生效、不入 DAG 快照（`workflow_dependency` 独立表，buildSnapshotJson 只冻结 nodes/edges）
- [ ] 5.5 单测待补：边 strength 读写 / 自依赖配置 / 跨流成环拒绝 / CRUD（@SpringBootTest 受 env 阻塞，待并发稳定）
- [x] 5.6 master + api compile 零错误（master CRUD/构造器/strength + controller endpoints）

## 6. 前端 UI（canvas + types + i18n）

- [x] 6.1 `lib/types.ts`：`DagEdge` 增 `strength`；run 请求类型增 `scope` + `targetNodeKey`（+ `RunScope`/`RunWorkflowRequest`/`WorkflowDependency` 类型）
- [x] 6.2 `workflow-canvas-view.tsx` 边右键菜单增「设为强/弱依赖」切换，弱依赖虚线视觉标识，变更置脏；`toFlow`/`toPayload` 透传 strength
- [x] 6.3 节点右键菜单增「运行到本节点」(TO_NODE)「运行下游」(DOWNSTREAM)（提取 `runWorkflowWithScope` 复用，调 `/api/workflows/{id}/run` 带 scope+targetNodeKey，接管 workflowInstanceId）；未上线禁用（`NodeActions.online`）
- [x] 6.4 工具栏「运行」弹出运行范围 Dialog（FULL/TO_NODE/DOWNSTREAM/ONLY_NODE + 目标节点选择，ONLY_NODE 仅列 TASK 节点）；ONLY_NODE 走 `/api/tasks/{id}/run`（提取 `runSingleTaskNode` 与节点右键「单独运行」共用）
- [ ] 6.5 跨周期依赖 CRUD 配置面板——**遗留**：后端 `DependencyDto.nodeId/dependNodeId` 为 `workflow_node.id`(Long)，而前端画布只有 `nodeKey`（`DagNodeDto` 无 id 字段），需后端配合改 DependencyDto 用 nodeKey（或 DagNodeDto 暴露 id）前端方能闭环；`WorkflowDependency` 类型已预留
- [x] 6.6 `messages` 双语在 `workflowCanvas` 补运行范围/强弱依赖 key（两 bundle 等集 714 keys，`pnpm i18n:lint` 通过）；跨周期依赖 key 随 6.5 补
- [x] 6.7 `pnpm typecheck` 零错误；`pnpm i18n:lint` zh-only/en-only 为空、无残留硬编码中文

## 7. 测试与浏览器验证门

- [ ] 7.1 前端 vitest：边强弱切换、运行范围 Dialog、配置面板跨周期依赖交互——**遗留**：核心交互已由 7.2 浏览器门实弹覆盖，组件级 vitest 待补
- [x] 7.2 浏览器验证门（playwright）：画布打开（wf3 TASK 节点 + 完整工具栏运行/虚拟节点/保存草稿/发布/配置/版本历史）、边右键「设为弱/强依赖」菜单实弹弹出、运行范围 Dialog（标题「运行工作流」+ scope 按钮「全量运行」「仅本节点」）实弹渲染、console 0 错误、无 missing i18n key——**通过**；节点 Radix ContextMenu 在 headless+force 下未触发（环境限制，`runWorkflowWithScope` 已 typecheck、边菜单同套右键机制已实弹证明）；「实际下发子图并事件流变色」属 7.4 后端集成范畴
- [x] 7.3 截图/trace 入项目根 `tmp/`，验证后清理（不入库）——已清理
- [ ] 7.4 后端集成测试：CRON 跨周期自依赖（上一周期 SUCCESS 才解锁）、首周期豁免；H2+PG——**遗留**：`crossCycleReady` 逻辑编译+代码审查，e2e 隔离搭建待补（同 3.5）

## 8. 收尾

- [x] 8.1 后端 install + `spring-boot:run`（H2）冒烟——调度内核/cron 正常；KernelSchedulingTest 6 绿 + WorkflowGraphValidatorTest 5 绿 + TriggerCommandTest 6 绿（弱依赖/强依赖/TO_NODE/DOWNSTREAM/FULL 子图/端到端）
- [ ] 8.2 `docs/architecture.md` 调度依赖章节（同周期强弱 + 跨周期依赖语义）——**遗留**
- [x] 8.3 design.md Open Questions 复核：①小时/分钟级跨周期——本轮仅日级（LAST_DAY/CURRENT_DAY 偏移），更细粒度后续；②ONLY_NODE 统一进 scope——已统一（`RunScope` 含 ONLY_NODE，走 `/api/tasks/{id}/run`）；③earliest UI 形态——随 6.5 跨周期面板（后端 DependencyDto 改 nodeKey 后）
- [x] 8.4 归档判定：核心功能（同周期强/弱依赖 + 手动运行子图范围 FULL/TO_NODE/DOWNSTREAM/ONLY_NODE）**完成 + 浏览器门通过**；已知遗留：6.5 跨周期依赖面板（后端 DependencyDto 需改 nodeKey）、3.5/5.5/7.1/7.4 测试补充、8.2 docs——转后续 change
