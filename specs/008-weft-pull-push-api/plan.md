# Implementation Plan: Weft 子特性 C —— 服务器 pull/push 同步 API

**Branch**: `008-weft-pull-push-api` (main 直推,单目录) | **Date**: 2026-06-27 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/008-weft-pull-push-api/spec.md`

## Summary

C 提供项目粒度的服务器侧 **pull / push / diff** HTTP 端点:把项目在「服务器治理真相源」与「本地纯文本工作副本」之间往返。pull 把一个项目的类目/任务/任务流/标签装配成 `ProjectExport`,经子特性 B 的 `FileContract.serialize` 产出文件集;push 经 `FileContract.deserialize` + 校验,把数据源逻辑名(= `Datasource.name`)解析回 `datasourceId`,在一个事务里**幂等覆盖**重建该项目定义并**为受影响 task/workflow 写不可变版本快照**(但**不**晋级 ONLINE);diff 给出本地 vs 服务器的增/改/删只读预览。隔离走现有 `TenantContext`,传输为单 JSON `files{path→content}` + 基线令牌 + force。技术核心:① 从 `publish()` 抽出**状态中立的建快照内核**供 push 复用(D1);② push 的 synthetic-id(B 给的合成 id)↔ 真实 DB 行的**按身份匹配落库**算法(D2)。

## Technical Context

**Language/Version**: Java 25(Spring Boot 4.0 / Spring Framework 7,Jackson 3 `tools.jackson`)

**Primary Dependencies**: 复用 `com.dataweave.master.filecontract`(子特性 B,纯转换库)、`TaskService`/`WorkflowService`(版本快照内核)、`*Repository`(Spring Data JDBC)、`TenantContext` + `JwtAuthFilter`(隔离)、`ApiResponse`/`BizException`(REST 约定)。**新增 0 个第三方依赖**。

**Storage**: PostgreSQL(默认)/ H2(`profiles=h2` 测试)。复用既有表:`projects` `catalog_nodes` `tags` `entity_tags` `task_def` `task_def_version` `workflow_def` `workflow_def_version` `workflow_node` `workflow_edge` `datasources`。**新增表:0**(版本快照复用现有 version 表)。

**Testing**: JUnit 5 + AssertJ;`@SpringBootTest`(h2 profile)做 pull→push→pull round-trip 集成测试 + WebTestClient/MockMvc 端点契约测试 + 隔离/校验/并发/删除守卫单测。

**Target Platform**: Linux 服务端(`dataweave-api` 进程,端口 8000)。

**Project Type**: web-service(后端 only;无前端改动)。

**Performance Goals**: 单项目定义量级(数十~数百 task/workflow),pull/push 单请求 P95 < 2s;非海量场景,不引入分页/流式。

**Constraints**: push 必须**事务性全有或全无**(强一致,不部分落库);pull 文件**0 凭据泄漏**;round-trip 复用 B 的 R3 字节稳定;隔离越权 100% 拒。

**Scale/Scope**: 3 个 REST 端点(pull/push/diff)+ 1 个 application service(`ProjectSyncService`)+ publish 内核的状态中立抽取 + DTO/契约 + 测试。预计落点:`dataweave-master/application`(SyncService、SnapshotCore 抽取)+ `dataweave-api/interfaces`(`ProjectSyncController`)+ 必要的 repository 派生查询补充(EntityTag 按 entity 删等)。

## Constitution Check

*GATE: 须在 Phase 0 前通过;Phase 1 设计后复检。*

| 原则 | 适用性 | 本计划如何遵守 |
|------|--------|----------------|
| **I. Files-First** | pull/push 直接服务文件化 | 文件格式 100% 复用 B,C 不另立格式;目录树=类目树由 B 保证;C 不引入任何"DB-only 才能表达"的定义 |
| **II. Server is Source of Truth** | C 的核心 | 版本快照 + 租户/项目隔离由服务器强制;`push` 幂等覆盖+生成快照、**禁双向同步/合并**(FR-008);覆盖前可感知差异(diff,US3/FR-010);越权拒(FR-012) |
| **III. Two-Legged Debugging** | 不直接涉及 | C 只交付服务器端点;本地 runtime 属 D。C 的 push/pull 契约为 D 的 CLI 消费铺路,不冲突 |
| **IV. AI Lives in Local Agent** | 不直接涉及 | C 不引入任何服务端 AI;端点为 E 的 MCP 工具复用铺路 |
| **V. Reuse the Kernel** | 强约束 | 快照走 `TaskService/WorkflowService`(D1 抽取状态中立内核,**不重写**快照机制);隔离走 `TenantContext`;REST 走 `ApiResponse`。**写操作经写闸门**:见下方"写闸门"裁定 |

**写闸门(PolicyEngine)适配裁定**:constitution V 要求"经 CLI/MCP 的写操作必须过写闸门"。C 此期交付的是**面向已认证开发者的 REST 端点**(等价于现有 `TaskController`/`WorkflowController` 的直接 CRUD,后者也不逐次过 `GatedActionService`)。push 作为写操作,其**经 MCP 暴露给 AI agent 的闸门拦截属子特性 E** 的职责(E "向本地 AI agent 暴露操作工具,写操作经写闸门")。本期 C 保证:① push 全程 `agent_action` 可审计;② 不绕过隔离与校验。**记录为有意边界**:C 的 REST 直连等同既有 controller 信任级;闸门强化随 E 落地。→ 见 Complexity Tracking。

**No Legacy Migration(constitution 硬约束)**:C 不提供任何存量迁移路径(FR-014);pull/push 仅服务转型后定义。✅

**Sub-spec 隔离 & 依赖顺序**:C 依赖 B(已落地 commit 52134cd)。当前仓库唯一在飞特性=Weft,单目录 main 直推(worktree SHOULD 为已记录的有意偏离)。交叉面:C 复用 B 的 `filecontract` 包(只读复用,不改 B);C 触及 `TaskService`/`WorkflowService`(抽取重构,须保持既有 publish 行为不回归)。✅

**Gate 结论**:PASS(1 项有意边界:写闸门强化延至 E,已在 Complexity Tracking 登记)。

## Project Structure

### Documentation (this feature)

```text
specs/008-weft-pull-push-api/
├── plan.md              # 本文件
├── research.md          # Phase 0:D1..D7 设计决策
├── data-model.md        # Phase 1:DTO/聚合/落库匹配算法/快照映射
├── quickstart.md        # Phase 1:pull→push→pull 验收脚本
├── contracts/           # Phase 1:pull/push/diff 端点 + JSON schema
│   ├── pull.md
│   ├── push.md
│   ├── diff.md
│   └── sync-bundle.schema.json
├── checklists/requirements.md
└── tasks.md             # Phase 2(speckit-tasks 生成,非本命令)
```

### Source Code (repository root)

```text
backend/
├── dataweave-master/
│   └── src/main/java/com/dataweave/master/
│       ├── application/
│       │   ├── ProjectSyncService.java        # 新增:pull/push/diff 编排(装配 ProjectExport ↔ 落库)
│       │   ├── ProjectSyncDtos.java           # 新增:PullResult/PushCommand/PushResult/DiffPreview 记录
│       │   ├── TaskService.java               # 改:抽出 writeTaskVersionSnapshot 状态中立内核(D1)
│       │   └── WorkflowService.java           # 改:抽出 writeWorkflowVersionSnapshot 状态中立内核(D1)
│       ├── domain/
│       │   ├── EntityTagRepository.java       # 改:补 findByEntityTypeAndEntityId / deleteByEntityTypeAndEntityId
│       │   ├── DatasourceRepository.java      # (复用 findByProjectId,可能补 findByProjectIdAndName)
│       │   └── *Repository.java               # 复用既有 findByProjectId* 查询
│       └── filecontract/                      # 子特性 B:只读复用,本期不改
│   └── src/test/java/com/dataweave/master/
│       └── application/ProjectSyncServiceTest.java   # 新增:round-trip/校验/删除守卫/并发 单测+集成
└── dataweave-api/
    └── src/main/java/com/dataweave/api/interfaces/
        └── ProjectSyncController.java         # 新增:POST /api/projects/{id}/pull|push|diff
    └── src/test/java/com/dataweave/api/
        └── ProjectSyncControllerTest.java     # 新增:端点契约 + 隔离越权 + ApiResponse 信封
```

**Structure Decision**: 沿用四模块 DDD —— 编排/落库/快照内核在 `dataweave-master`(application + domain),HTTP 端点在 `dataweave-api`(interfaces)。零新表、零新依赖、零新模块(符合 Reuse the Kernel)。

## Complexity Tracking

| 偏离 | 为何需要 | 被拒的更简方案 |
|------|----------|----------------|
| push 写操作本期走 REST 直连,不逐次过 `GatedActionService` 写闸门 | C 的消费者是已认证开发者(与现有 TaskController/WorkflowController 同信任级);闸门是为 AI agent 来源设计,属 E 的职责;本期强行接闸门会与尚未定型的 E 工具契约耦合 | "C 即接全闸门":被拒,因 E 尚未定 MCP 工具边界,过早耦合;且现有同类 REST CRUD 均未逐次过闸门,C 单独接会造成约定不一致。push 仍留 `agent_action` 审计 + 隔离 + 校验,安全基线不降 |
| 从 `publish()` 抽取状态中立建快照内核(改 TaskService/WorkflowService) | Q1 裁定 push 生成快照但不晋级 ONLINE;现有 publish 把"建快照"与"晋级 ONLINE + 引用闸门"耦合,无法直接复用 | "push 复制一份建快照逻辑":被拒,违反 DRY 与 Reuse-the-Kernel,且两处快照逻辑漂移风险高。抽取后 publish = 建快照内核 + 晋级,行为不回归 |
