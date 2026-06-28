# Implementation Plan: Weft 子特性 E —— MCP 工具重塑

**Branch**: `010-weft-mcp-tools` | **Date**: 2026-06-27 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/010-weft-mcp-tools/spec.md`

## Summary

E 把平台能力以 MCP 工具暴露给本地 AI agent,取代被拆的服务端 AI。核心四件事:① **给 MCP 建立租户身份**(现状只有单一共享 token、无租户 → 跨租户漏洞根因),分发工具前注入 `TenantContext`;② **重塑工具集** —— 移除内联建任务的 `create_task`,新增 `project_pull/push/diff`(复用 C `ProjectSyncService`)+ `instance_logs`,保留并给 query_* 补隔离;③ **`project_push` 风险自适应闸门** —— 两条 `policy_rules`(L1 纯增改 / L2 含删除或 force),PolicyEngine 零改,执行经 `DefaultPlatformActionExecutor` 回调 push;④ 所有写经写闸门+审计、所有读受租户隔离。**C/PolicyEngine 内核零修改,仅数据驱动 seed + 工具层接线。**

## Technical Context

**Language/Version**: Java 25(`dataweave-api` MCP 层 + `dataweave-master` seed/执行器接线)

**Primary Dependencies**: 既有 `McpToolRegistry`/`McpTool`/`McpController`/`McpAuthFilter`;`GatedActionService`/`PolicyEngine`;C 的 `ProjectSyncService`;`TenantContext`;`Messages`(i18n)

**Storage**: 无新表。`policy_rules` 增 2 行 seed(PROJECT_PUSH / PROJECT_PUSH_DESTRUCTIVE);agent_action 审计复用既有 4 表

**Testing**: JUnit5 + AssertJ + WebTestClient。覆盖:tools/list 无残留 AI 工具;project_push L1 直通 / 含删除→L2 PENDING 且 0 落库;跨租户调用被拒(含既有 query_*);只读同源口径

**Target Platform**: Spring Boot 4 / WebFlux(`/mcp` JSON-RPC,Bearer)

**Project Type**: 后端单模块为主(api MCP 层)+ master 少量接线(seed/执行器 case)

**Performance Goals**: 非热路径;只读复用域服务,写经闸门(与现有 MCP 写同量级)

**Constraints**: 写零旁路闸门;读零跨租户;C/PolicyEngine 零修改;data 驱动定级

**Scale/Scope**: 工具约 8–10 个;单开发者 AI agent 并发量级

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

宪法 **v1.1.0**,逐条:

- **I. Files-First** ✅ 移除 create_task 内联写入,定义一律走 `project_push`(文件化),**强化**原则 I。
- **II. Server is Source of Truth** ✅ project_push 复用 C 幂等覆盖+快照+隔离;读受 tenant 限定。
- **III. Two-Legged Debugging** ✅ 不涉本地 runtime(D 管);不损观测(instance_logs 复用 ops 只读)。
- **IV. AI Lives in Local Agent** ✅ E 正是此原则的落地 —— AI 经 MCP 操作平台;确认 MCP 侧无残留服务端 AI 工具;不损 ops/metrics/日志/DAG。
- **V. Reuse the Kernel** ✅ 复用闸门/审计/C/MCP 框架;**写零旁路**(FR-005),风险自适应靠 policy_rules 数据驱动,PolicyEngine 不重写;node_exec 安全解析不弱化。

**结论:PASS**,无违规。Phase 1 后复核:身份注入/seed/工具接线均未引入新违规,维持 PASS。

## Project Structure

### Documentation (this feature)

```text
specs/010-weft-mcp-tools/
├── plan.md  research.md  data-model.md  quickstart.md
├── contracts/           # MCP 工具契约 + 风险定级表 + 复用端点
└── tasks.md             # speckit-tasks 产出
```

### Source Code (repository root)

```text
backend/dataweave-api/src/main/java/com/dataweave/api/
├── application/mcp/McpToolRegistry.java       # 删 create_task;增 project_*/instance_logs;query_* 补隔离
├── application/mcp/McpTool.java               # McpContext 增 tenantId()/userId()
├── infrastructure/McpAuthFilter.java          # token 绑定身份;放行后置 tenant/user 到 exchange/context
└── interfaces/McpController.java              # 分发前 TenantContext.set(身份);finally clear

backend/dataweave-master/src/main/java/com/dataweave/master/
├── application/DefaultPlatformActionExecutor.java  # 新增 PROJECT_PUSH / PROJECT_PUSH_DESTRUCTIVE case → ProjectSyncService.push
└── resources/db/...(或 data.sql seed)              # policy_rules 两条 seed

backend/dataweave-api/src/test/java/com/dataweave/api/mcp/
├── McpToolsListNoLegacyTest.java              # tools/list 无 create_task/无 AI 工具
├── McpProjectPushGateTest.java                # L1 直通 / 含删除→L2 PENDING 0 落库 / 校验拒
├── McpTenantIsolationTest.java                # 跨租户(含既有 query_*)被拒
└── McpReadSameSourceTest.java                 # 只读与 REST 同源口径
```

**Structure Decision**: 主战场 `dataweave-api` MCP 层(工具增删、身份注入、隔离回补);`dataweave-master` 仅两处轻接线(执行器 case + policy_rules seed)。复用 C `ProjectSyncService` 与 PolicyEngine,均零修改。`McpController` 在分发前由 `McpAuthFilter` 解析的身份设置 `TenantContext`、finally 清理,保证每次 MCP 调用 thread-local 干净。

## Complexity Tracking

> 无违规。唯一需用户知会的非平凡决策是 **E1:给 MCP token 绑定租户身份**(把单一共享密钥扩为带身份密钥)—— 不是违宪,是 FR-007 隔离的必要前提,已在 research.md 标注并供用户调整身份模型。

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| (none) | — | — |
