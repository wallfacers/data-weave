# Implementation Plan: 子特性 A —— 服务端 AI 拆除

**Branch**: `006-weft-ai-teardown`(基线分支 `005-weft-pivot`) | **Date**: 2026-06-27 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/006-weft-ai-teardown/spec.md`

## Summary

纯拆除子特性。把服务端内置 AI 整套(AG-UI、workhorse 桥接、IntentRouter、主动发现 Inspector→Finding→Diagnosis→FindingAction→AgentNotifier、OpsAlertService)从前后端移除并净化数据/配置/文档,保留运行态观测与写闸门。技术执行采用**按域切 3 条独立 git worktree 并发** + **主 agent 收口集成 pass**;三条 worktree 文件所有权**完全不相交**,合并零冲突。

## Technical Context

**Language/Version**: Java 25(后端)、TypeScript/React 19(前端)

**Primary Dependencies**: Spring Boot 4.0 / Spring Framework 7 / WebFlux / Spring Data JDBC(后端);Next.js 16 / shadcn base / hugeicons(前端)

**Storage**: PostgreSQL(默认)· H2(`profiles=h2`)· Redis(EventBus/LogBus)

**Testing**: JUnit 5 + AssertJ(后端);pnpm typecheck + build + 浏览器验证 gate(前端)

**Target Platform**: Linux server(后端 `:8000`)、浏览器(前端 `:4000`)

**Project Type**: Web application(backend 多模块 + frontend)

**Performance Goals**: N/A(拆除不改性能目标)

**Constraints**: 拆除后端整体编译/启动正常、前端 typecheck/build/浏览器渲染通过、运行态观测与写闸门毫发无损

**Scale/Scope**: ~50 个后端文件(含测试)、~22 个前端文件、若干配置/文档、3 张表删除 + 1 张新表

## Constitution Check

*GATE: 对齐 `.specify/memory/constitution.md` v1.0.0*

| 原则 | 本特性如何满足 |
|------|----------------|
| **IV. AI Lives in the Local Agent**(NON-NEGOTIABLE) | A 正是兑现此原则:删除全部服务端 AI;**且不损伤运行态观测**(ops/metrics/日志/DAG)与调度内核 —— 通过 OpsController 逐端点裁剪、保留观测端点实现 |
| **V. Reuse the Kernel** | 保留 `PolicyEngine`/`GatedActionService`/`ApprovalService` + `agent_action` 写闸门;保留 MCP 框架(`McpController`/`McpToolRegistry`/`McpAuthFilter`),仅剪悬挂工具注册 |
| **Additional Constraint · No Legacy Migration** | A 只删 AI 专属表;存量任务/任务流定义数据不在 A 删(留 B/C),A 期间系统仍可启动演示 |
| **Naming & repo** | A 内更新 CLAUDE.md 更名 Weft |

**Gate 结论**:通过,无违例。Complexity Tracking 留空。

## Project Structure

### Documentation (this feature)

```text
specs/006-weft-ai-teardown/
├── spec.md              # 已完成
├── plan.md              # 本文件
├── data-model.md        # workspace_snapshot 新表
├── checklists/requirements.md   # 已完成
└── tasks.md             # speckit-tasks 输出(下一步)
```

### 三条 worktree 的文件所有权矩阵(关键:保证合并零冲突)

| Worktree | 分支 | 路径 | 独占文件域 |
|----------|------|------|-----------|
| **WS-FE** 前端 | `006-a-frontend` | `../dw-weft-fe` | `frontend/**`(含 package.json、next.config) |
| **WS-BE** 后端 | `006-a-backend` | `../dw-weft-be` | `backend/**`(含各模块 resources/application*.yml) |
| **WS-DOC** 文档配置 | `006-a-docs` | `../dw-weft-doc` | `docker-compose.yml`、`deploy/**`、`CLAUDE.md`、`README.md`、`docs/**` |

三域**无交集** → 三分支可无冲突合并。`specs/**` 与 `.specify/**` 由主 agent 持有(已提交,三 worktree 不动)。

**Structure Decision**:三 worktree 均自 `005-weft-pivot` HEAD(含 005 总纲 + 006 spec)切出;并发完成后由主 agent 依次合并回 `005-weft-pivot` 并跑收口集成 pass。

---

## 各 Worktree 精确清单

### WS-FE 前端(独占 `frontend/**`)

**删除文件**
- `components/agent-rail.tsx`、`components/agent-chat.tsx`
- `components/chat/`(整目录:attachment-chip / chat-input / chat-shell / highlighter / markdown-content / markdown-stream / message-list / message-part / permission-part / session-switcher)
- `lib/chat/`(整目录:mock / mode / provider / real / store / types)
- `components/cockpit/`(diagnosis-card / findings-rail / fix-actions / fleet-card)

**修改文件**
- `components/app-shell.tsx`:移除 `<AgentRail/>` 左栏,`<main>` Workspace 占满全宽(SidePanel 去留:若仅承载 findings 则一并去,否则保留)
- `package.json`:移除 `@copilotkit/*`、`@ag-ui/*`、聊天专用依赖(marked/morphdom/DOMPurify/Shiki 若仅聊天用则删,被他处复用则留 —— 删前 grep 确认)
- `next.config.*`:移除指向 `/agui`、`/api/agent/stream` 的 rewrite/proxy(若有)
- 任何 import 已删组件的残留引用(跟 typecheck 报错逐个清)

**WS-FE 验证 gate**
- `cd frontend && pnpm install && pnpm typecheck` 零错
- `pnpm build` 通过
- 浏览器验证:首页 Workspace 占满、无左栏聊天、**无 console 报错**、打开 tab→刷新后状态仍在(依赖 WS-BE 的 workspace_snapshot;若后端未就绪,先验证不报错,快照恢复留集成 pass 复验)

### WS-BE 后端(独占 `backend/**`)

**删除文件 · dataweave-api**
- `interfaces/AguiController.java`、`interfaces/AgentSessionController.java`、`interfaces/AgentStreamController.java`、`interfaces/DiagnosisController.java`、`interfaces/FindingController.java`
- `application/AguiOrchestrator.java`、`application/AguiEvents.java`、`application/IntentRouter.java`、`application/OpsAlertService.java`、`application/WorkhorseDiagnosisAnalyzer.java`
- `application/bridge/`(整目录:WorkhorseBridge / WorkhorseClient / WorkhorseEvent / WorkhorseHealth / WorkhorseHealthProbe / WorkhorseHttpClient)
- `application/supervisor/WorkhorseSupervisor.java`
- 测试:`AgentSessionEndpointTest`、`AguiDegradeTest`、`AguiEndpointTest`、`AguiWorkhorseModeTest`、`FindingEndpointTest`、`IntentRouterIntentTest`、`IntentRouterUiOpenTest`、`WorkhorseDiagnosisAnalyzerTest`、`bridge/WorkhorseBridgeUiOpenTest`、`bridge/WorkhorseHttpClientMetadataTest`、`supervisor/WorkhorseSupervisorRealProcessTest`

**删除文件 · dataweave-master**
- `application/`:AgentNotifier / AgentSessionService / DiagnosisAnalyzer / DiagnosisService / MockDiagnosisAnalyzer / FindingActionService / FindingService / Inspector / InspectorScheduler / TaskFailureInspector
- `domain/`:AgentSession / AgentSessionRepository / Finding / FindingRepository / TaskDiagnosis / TaskDiagnosisRepository
- 测试:`DiagnosisServiceApplyFixTest`、`DiagnosisServiceTest`、`FindingServiceTest`、`InspectorSchedulerTest`、`TaskFailureInspectorTest`

**删除文件 · dataweave-alert**
- `application/OpsAlertListener.java`(随 OpsAlertService 一并去;删前确认 alert 模块无其它活跃消费者)

**修改文件 · 逐端点裁剪与剪枝**
- `interfaces/OpsController.java`:删 `/supervisor`、`/inspect` 端点及其对 WorkhorseSupervisor/OpsAlertService 的注入;**保留** summary/periodic-workflows/failed/metrics/logs-stream/events-stream 等观测端点
- `application/mcp/McpToolRegistry.java`:剪掉悬挂工具注册(`query_diagnosis` 及任何引用 DiagnosisService/FindingService 的工具);**保留**框架与其余查询/写工具
- `application/AgentAuditService.java`:**职责拆分** —— 删除 session/run/step 审计部分;workspace_state 读写迁入**新建** `application/WorkspaceSnapshotService.java`(+ `domain/WorkspaceSnapshot.java` + Repository)
- `resources/schema.sql`(及 `data.sql` 若含相关 seed):新增 `workspace_snapshot` 表(见 data-model.md);删 `agent_session`/`agent_run`/`agent_step`、`finding`/`task_diagnosis` 表 DDL;**保留** `agent_action`/`policy_rules`/审批表
- `resources/application*.yml`:删 `agent.mode` 及 workhorse 相关配置;清理 workhorse WebClient `@Bean` 接线(`WebClientConfig` 若仅为 workhorse 存在则删对应 bean)
- 任何注入已删 bean 的保留类(跟 `mvnw compile` 报错逐个清)

**新增**
- `application/WorkspaceSnapshotService.java` + `domain/WorkspaceSnapshot.java`(+ Repository):承载前端 Workspace tab 快照(替代 agent_session.workspace_state)
- 回归测试:`/agui`、`/ops/supervisor`、`/ops/inspect` 返回 404;`/api/ops/metrics`、日志流、DAG 实例流 200;经 MCP/REST 写操作仍写 `agent_action`

**迁移时序(硬约束)**:先建 `workspace_snapshot` 表 + WorkspaceSnapshotService 并切换前端读写路径所依赖的端点 → 再删 `agent_session` 表。

**WS-BE 验证 gate**
- `cd backend && ./dev-install.sh`(或 `./mvnw -q -pl dataweave-api,dataweave-master,dataweave-alert -am compile`)零错
- `./mvnw -pl dataweave-api spring-boot:run`(或 h2 profile)可启动,无 bean 接线报错
- 新增回归测试通过

### WS-DOC 文档配置(独占根配置/文档)

**修改文件**
- `CLAUDE.md`:① 顶部定位从"AI-Agent-native 数据中台"改为 **Weft · 任务即代码平台**;② 删 AG-UI 协议、自建聊天台、workhorse、IntentRouter、主动发现/findings、Agent brain modes、agent-fabric 相关条目;③ 知识库导航表删对应行;④ 保留调度/执行/观测/MCP/写闸门条目
- `docker-compose.yml`:删 `workhorse` profile/service
- `README.md`:同步更名与删除 AI 段落(若有)

**删除**
- `deploy/workhorse/`(整目录:config.yaml + mcp.json)

**WS-DOC 验证 gate**
- `grep -riE "ag-ui|agui|workhorse|intentrouter|copilotkit" CLAUDE.md docker-compose.yml deploy README.md` 无命中(注释/历史除外)
- `docker compose config` 解析通过(无 workhorse profile 残留语法错)

---

## 收口集成 pass(主 agent,串行)

1. **合并**:`005-weft-pivot` 上依次 `git merge 006-a-frontend 006-a-backend 006-a-docs`(预期零冲突,因文件域不相交;若冲突则人工裁决)
2. **后端整体**:`cd backend && ./dev-install.sh` 零错;`./mvnw -pl dataweave-api spring-boot:run -Dspring-boot.run.profiles=h2` 可启动
3. **前端整体**:`cd frontend && pnpm install && pnpm typecheck && pnpm build`
4. **端到端浏览器验证**(Browser Verification Gate,硬):首页 Workspace 占满、无聊天残留、无 console 报错;tab 快照经 `workspace_snapshot` 持久化恢复
5. **拆除断言**:`POST /agui`、`GET /api/ops/supervisor`、`GET /api/ops/inspect` → 404;`GET /api/ops/metrics`、日志流、DAG 实例流 → 200
6. **写闸门回归**:经 MCP(`POST /mcp` 一个写工具)或 REST 触发写操作 → `agent_action` 落一条、审批流不变
7. **净化断言**:全仓库 `grep -riE "agui|workhorse|intentrouter|inspector|findingservice|diagnosisservice|agentnotifier|opsalert"` 仅余历史/注释为 0 活跃引用;启动建表无 agent_session/run/step/finding/task_diagnosis
8. **worktree 清理**:`git worktree remove ../dw-weft-fe ../dw-weft-be ../dw-weft-doc`,删除三临时分支
9. **提交**:收口提交 + 更新 tasks.md 勾选

## Complexity Tracking

> 无 Constitution 违例,留空。
