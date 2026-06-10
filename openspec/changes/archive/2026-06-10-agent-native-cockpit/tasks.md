## 1. 企业级数据模型与种子（全量 schema）

- [x] 1.1 数据模型设计真相源 `design-data-model.md`：27 表（RBAC+项目隔离 / 数据源 / 任务·任务流DAG / 指标分层 / 资源诊断 / 告警 / 审计），公共审计列约定
- [x] 1.2 两级状态机设计：节点实例 6 态（NOT_RUN/WAITING/RUNNING/SUCCESS/FAILED/STOPPED）+ 工作流实例由 DAG 聚合的规则矩阵
- [x] 1.3 `schema.sql` 全量 DDL（PG/H2 兼容）+ `data.sql` 一致种子（失败→诊断现场、两级状态样例、RBAC、原子/派生指标）
- [x] 1.4 在 Docker 真实 PostgreSQL 验证：DDL `ON_ERROR_STOP` 无报错、27 表建成、关键数据自洽

## 1b. 领域层重建（按新 schema 搭积木）

- [x] 1b.1 RBAC 域实体+仓储：Tenant/Project/User/Role/Permission/UserRole/RolePermission/ProjectMember
- [x] 1b.2 数据源域：DatasourceType/Datasource 实体+仓储+服务
- [x] 1b.3 任务·任务流域：TaskDef/TaskDefVersion/WorkflowDef/WorkflowDefVersion/WorkflowNode/WorkflowEdge/WorkflowInstance/TaskInstance/WorkflowDependency 实体+仓储（替换旧 Task/TaskInstance）
- [x] 1b.4 指标域：Dimension/AtomicMetric/DerivedMetric/MetricDimension/MetricLineage（替换旧 Metric/MetricLineage），MetricService 迁到 atomic_metrics
- [x] 1b.5 资源诊断域：WorkerNode/TaskDiagnosis（对齐新列名 node_code/task_instance_id + 审计列）
- [x] 1b.6 告警域：NotificationChannel/AlertRule（JDBC 实体）+ NotificationSender 行为接缝
- [x] 1b.7 调整 IntentRouter/MetricService/LineageService/TaskService/SqlExecutionService 到新表，`@EnableJdbcRepositories` 扫 master+alert 两包
- [x] 1b.8 工作流状态聚合服务 WorkflowStateService：按设计矩阵从 task_instance 计算 workflow_instance.state（9 单测通过）
- [x] 1b.9 `./mvnw install -DskipTests` 五模块全 SUCCESS；pg profile 启动通过（4 类 Agent 意图实跑 + 写库验证）

## 2. Worker 机器注册与心跳上报（worker-fleet）

- [x] 2.1 `dataweave-worker` `HeartbeatReporter`：JDK HttpClient POST `/api/fleet/heartbeat` 幂等 upsert（按 nodeCode），默认 `dataweave.worker.heartbeat.enabled=false`
- [x] 2.2 周期上报心跳 + 资源指标，`fixedDelay` 默认 10s；`@EnableScheduling` 落在启动类
- [x] 2.3 master 判离线：`FleetReaperJob` @Scheduled 调 `FleetService.markStaleOffline()`，阈值 30s；`FleetHeartbeatSimulator` 保活在线节点供驾驶舱演示
- [x] 2.4 `FleetController`：GET `/api/fleet`、GET `/api/fleet/{nodeCode}`、POST `/api/fleet/heartbeat`（实跑：5 节点，node-3 内存 95%、node-4 OFFLINE）
- [x] 2.5 `install -DskipTests` 五模块全 SUCCESS；FleetServiceTest 3 绿；修复 4 实体审计列 String→Long（worker_nodes UPDATE 类型冲突）

## 3. 自诊断领域与编排（self-diagnosis）

- [x] 3.1 `DiagnosisService`：采集失败实例上下文（实例日志 + 节点指标 + 并发争抢）→ 写 `task_diagnosis`，按 taskInstanceId 幂等
- [x] 3.2 `DiagnosisAnalyzer` 接缝 + `MockDiagnosisAnalyzer`：OOM/资源争抢规则产出根因 + context + 建议 JSON（真模型只换实现）
- [x] 3.3 `applyFix(id, action)`：RERUN/MIGRATE_NODE/RERUN_MORE_MEMORY/CAP_NODE_WEIGHT，迁移落最空闲节点、置诊断 RESOLVED（实跑：#1→RESOLVED，新实例 #100 SUCCESS@node-5）
- [x] 3.4 `IntentRouter` 诊断/查机器分支置于血缘之前（避关键词吞），注入 FleetService/DiagnosisService/ObjectMapper；CREATE_TASK 沿用既有分支
- [x] 3.5 `install` 五模块全 SUCCESS；IntentRouterIntentTest 3 绿；`DiagnosisController` GET/GET{id}/POST{id}/fix 实跑通过

## 4. AG-UI 事件扩展（self-diagnosis / worker-fleet）

- [x] 4.1 CUSTOM `dataweave.diagnosis`（kind/id/title/rootCause/workerNodeCode/context/suggestions）经 `AgentReply.customEventName` 透传，实跑负载正确
- [x] 4.2 CUSTOM `dataweave.fleet`（kind/columns/rows，节点 + 资源水位）实跑负载正确
- [x] 4.3 序列完整：查机器 RUN_STARTED→TEXT_MESSAGE_START→4×CONTENT→END→CUSTOM→RUN_FINISHED；诊断同构（实跑确认）
- [x] 4.4 CORS 放行 3000（HealthAndCorsTest 预检通过）；AguiEndpointTest WebTestClient 验证 GMV 全序列 + CUSTOM 负载（6 测试绿）

## 5. 前端三栏骨架与右舷 Agent（cockpit-shell / copilot-rail）

- [x] 5.1 `app-shell.tsx` 三栏 flex：左 sidebar + 中 SidebarInset + 右舷 `<AgentRail/>`（Suspense 包裹，header 无边框）
- [x] 5.2 CopilotKit v2 Provider+HttpAgent 在 `agent-chat.tsx`，经 `AgentRail` 挂在 layout 层常驻；selfManagedAgents + v2 styles + Shiki 主题透传
- [x] 5.3 右舷 `<AgentChat/>`（CopilotChat agentId="dataweave"）常驻；实跑确认输入框渲染、发「看看集群机器状态」收到流式回复（含 node-X）
- [x] 5.4 收起→悬浮球（bg-primary 圆形）⇄ 展开原地复位（实跑：collapse→textarea=0+ball，expand→textarea=1）
- [x] 5.5 上下文感知：`AgentRail` 读 `usePathname()`+`useSearchParams()`，标题行展示「当前：<模块> · <对象>」；页面用 `?instanceId=/?nodeId=` 联动
- [x] 5.6 删 `app/agent/page.tsx` + 移除菜单项（无 `/agent` 残留）；`pnpm typecheck` 零错误

## 6. 前端菜单分组与占位页（cockpit-shell）

- [x] 6.1 `app-sidebar.tsx` 分组：驾驶舱 + 数据研发（任务开发/调度运维/数据集成）/ 数据资产（指标体系/数据血缘/资产目录）/ 资源与诊断（集群机器/失败诊断/数据质量/数据服务），hugeicons
- [x] 6.2 路由可导航：`/`、`/tasks`、`/ops`、`/fleet`、`/diagnosis` 实跑均渲染
- [x] 6.3 占位项 `/integration` `/catalog` `/quality` `/service` → `<ComingSoon/>`，不抛错
- [x] 6.4 `pnpm typecheck` 零错误

## 7. 驾驶舱与观测页（cockpit-shell / worker-fleet / self-diagnosis）

- [x] 7.1 驾驶舱 `/`：概况计数卡（9 总/3 成功/1 失败/1 运行中）+ 失败任务表 + Agent 诊断中事项（读 `/api/ops/summary`）
- [x] 7.2 失败项点击 → `/diagnosis?instanceId=<id>`，右舷标题同步「当前：失败诊断 · <id>」
- [x] 7.3 `/fleet`：5 节点卡片，CPU/内存/磁盘 进度条、负载、规格；node-3 内存 95% 红色、node-4 OFFLINE（读 `/api/fleet`）
- [x] 7.4 `/diagnosis`：根因 + 证据(contextJson 解析) + 一键修复建议按钮 → POST `/api/diagnosis/{id}/fix`（读 `/api/diagnosis`）
- [x] 7.5 `/ops` 运行实例表（读 `/api/ops/instances`）；`/tasks` 任务定义列表 + 保留 Monaco SQL 工作台（新增 `OpsController` 补 REST）
- [x] 7.6 全程语义 token + 无分割线 + hugeicons（截图目检通过）；`pnpm typecheck` + `pnpm design:lint`（0 error/warning）通过

## 8. 联调、测试与验证

- [x] 8.1 后端 JUnit+AssertJ：`FleetServiceTest`(报告/离线判定 3) + `WorkflowStateServiceTest`(聚合 9) + `IntentRouterIntentTest`(诊断/查机器意图 3)
- [x] 8.2 后端 WebTestClient：`AguiEndpointTest`(全序列 + CUSTOM 负载) + `HealthAndCorsTest`(健康 + CORS 预检)；`mvnw test` 18 绿
- [ ] 8.3 前端：右舷收起/展开、上下文注入的 vitest（按需，暂以 Playwright 实跑覆盖）
- [x] 8.4 Browser Verification Gate：Playwright 实跑——右舷输入框渲染、发消息收到流式回复（含 node-X）、五页渲染、收起/展开、console/page error 均为 `[]`
- [x] 8.5 验证产物写 `tmp/`，验证后已清理（截图/脚本/日志），不留仓库
