# Implementation Plan: Companion Workhorse 生产收口

**Branch**: `feat/companion-workhorse-prod` | **Date**: 2026-07-16 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/072-companion-workhorse-prod/spec.md`

**Note**: HOW 设计见 `docs/superpowers/specs/2026-07-16-companion-workhorse-prod-design.md`(brainstorming 已批准),本 plan 是其 Spec Kit 落地。

## Summary

将 071 companion 的 workhorse sidecar 从「宿主进程 + host.docker.internal 回连」收口为「受管 docker 容器服务 + 服务名寻址」;同时收紧推理端点跨域/认证(白名单 + bearer,凭据 env 注入不出后端);并补全 mcp 平台工具通道让巡检查真实数据。全程遵守 constitution IV 运行态 sidecar 例外面。

## Technical Context

**Language/Version**: workhorse-agent(Go,prebuilt 二进制 `deploy/workhorse/bin/`);后端 Java 25 / Spring Boot 4(WebFlux);docker-compose 编排。

**Primary Dependencies**: workhorse-agent 二进制、DeepSeek(Anthropic 兼容端点)、docker compose(distributed profile)、MCP(workhorse → 后端 `/mcp`)。

**Storage**: PostgreSQL(元数据,沿用 071 schema 0.21.0:patrol_routine/run/report/companion_message);Redis(EventBus/LogBus)。**无 schema 变更,不 bump version**。

**Testing**: 后端 JUnit 5 + AssertJ(CompanionUs2IT/Us3IT 钉死 brain 必死端口,与 8300 解耦);容器端到端:docker compose up + 三步法 + 巡检真实数据验证。

**Target Platform**: Linux server(docker distributed 部署)。

**Project Type**: web-service(平台)+ sidecar(go 二进制,容器化)。

**Performance Goals**: 对话首 token 秒级(DeepSeek 推理);巡检单轮 ≤ 例程 timeout(默认 120s)。

**Constraints**: sidecar 不可用降级不阻塞调度(constitution IV ③);凭据不出后端(env/.env gitignored);未授权调用 100% 拒。

**Scale/Scope**: 单租户(tenant=1)本地/默认;多租户 token 动态切换后续。改动面:1 Dockerfile + 1 config 模板 + docker-compose(workhorse 服务 + master base-url/origin)+ WorkhorseBrainClient(Origin env)+ mcp.json(dataweave server)+ .env。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Evidence |
|---|---|---|
| IV. AI Lives in Local Agent(sidecar 例外面 1.3.0) | ✅ PASS | 服务端零推理,推理在 workhorse sidecar;编排(调度/提示/持久化/转发)在服务端。例外面三条全守:① 凭据 env/.env 注入,gitignored,不下发前端/出库(FR-002);② 写操作经 PolicyEngine + agent_action(companion.yaml 写工具,FR-004);③ 不可用降级不阻塞(MockBrain/兜底,FR-005)。不可让渡内核(服务端无大脑/AI 在 sidecar/不损观测调度)未改。 |
| V. Reuse the Kernel | ✅ PASS | 复用 PolicyEngine 写闸门、MCP server 框架、调度内核;无内核重写。 |
| II. Server Source of Truth | ✅ PASS | sidecar 经 `/mcp` 调平台工具,tenant/project 隔离由后端 McpAuthFilter + TenantContext 守。 |
| III. Two-Legged Debugging | N/A | 不涉及本地任务执行 runtime。 |
| I. Files-First | N/A | 不涉及定义文件。 |

**无 violation**,无需 Complexity Tracking。

## Project Structure

### Documentation (this feature)

```text
specs/072-companion-workhorse-prod/
├── plan.md              # 本文件
├── spec.md              # 需求(WHAT/WHY)
├── research.md          # Phase 0:技术决策记录
├── data-model.md        # Phase 1:无新实体(沿用 071 schema)
├── quickstart.md        # Phase 1:容器化端到端验证
└── contracts/           # Phase 1:workhorse HTTP + mcp 契约
    └── workhorse-api.md
```

### Source Code (repository root)

```text
deploy/workhorse/
├── Dockerfile           # 新增:alpine + prebuilt bin + config 模板
├── config.yaml          # 新增:模板(无敏感,进 git)
├── config.runtime.yaml  # 改:allowed_origins + 去 allow_null_origin(gitignored,serve-local 用)
├── mcp.json             # 改:加 dataweave server(gitignored)
├── agents/companion.yaml # 沿用(挂载进容器)
├── bin/workhorse-agent-linux-amd64  # 沿用(prebuilt)
└── serve-local.sh       # 沿用(本地裸机调试备选)

docker-compose.yml       # 改:加 workhorse 服务 + 两 master base-url/origin + 去 extra_hosts
backend/dataweave-master/src/main/java/com/dataweave/master/companion/infrastructure/
└── WorkhorseBrainClient.java  # 改:Origin 读 companion.brain.origin(env)
.env                     # 改:加 DEEPSEEK_API_KEY / COMPANION_BRAIN_ORIGIN(gitignored)
```

**Structure Decision**: 复用现有 `deploy/workhorse/`(加 Dockerfile + config 模板)+ docker-compose(加服务)+ WorkhorseBrainClient(小改)。无新模块/新包,符合「reuse the kernel」。

## Complexity Tracking

无 Constitution violation,本节 N/A。
