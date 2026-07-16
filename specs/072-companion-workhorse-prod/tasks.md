# Tasks: Companion Workhorse 生产收口

**Input**: Design documents from `/specs/072-companion-workhorse-prod/`(spec/plan/research/data-model/contracts/quickstart)

**Organization**: 按 user story 分组(US1 容器化部署 P1 / US2 安全收紧 P2 / US3 巡检真实数据 P3),每 story 独立可测。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行(不同文件,无未完成依赖)
- **[Story]**: 所属 user story(US1/US2/US3);Setup/Foundational/Polish 无 story 标签
- 描述含精确文件路径

---

## Phase 1: Setup(共享前置)

**Purpose**: secret 与构建素材就位

- [x] T001 配置项目根 `.env`(gitignored)加 `DEEPSEEK_API_KEY=<deepseek-key>` 与 `COMPANION_BRAIN_ORIGIN=http://dataweave-master:8000`
- [x] T002 [P] 确认 `deploy/workhorse/bin/workhorse-agent-linux-amd64` 存在(否则跑 fetch-bin.sh 获取)

---

## Phase 2: Foundational(容器化基础,阻塞所有 US)

**Purpose**: workhorse 镜像与配置模板(无敏感),所有 user story 的容器化前提

**⚠️ CRITICAL**: 未完成则 US1/US2/US3 不能开始

- [x] T003 创建 `deploy/workhorse/config.yaml` 模板(无敏感):`server.host: 0.0.0.0` + `port: 8300`、`providers.anthropic.{base_url: https://api.deepseek.com/anthropic, fast_model: deepseek-v4-flash, api_key: REPLACE}`、`models.default: anthropic:deepseek-v4-pro`、`allowed_origins: [http://dataweave-master:8000, http://dataweave-master-2:8200]`、`mcp.config_path: /app/mcp.json`、`agents.dir: /app/agents`、`auth.enabled: true`(bearer_token 走 env 不写文件)
- [x] T004 创建 `deploy/workhorse/Dockerfile`:`FROM alpine:3` + `apk add bash ca-certificates` + `COPY bin/workhorse-agent-linux-amd64 /app/workhorse-agent` + `COPY config.yaml mcp.json /app/` + `COPY agents /app/agents` + `chmod +x` + `ENTRYPOINT ["/app/workhorse-agent"]` + `CMD ["serve","--config","/app/config.yaml","--host","0.0.0.0","--port","8300"]`(依赖 T003 的 config.yaml)

**Checkpoint**: 镜像可构建、配置模板无敏感,US 可开始

---

## Phase 3: User Story 1 - 标准化容器部署全栈 (Priority: P1) 🎯 MVP

**Goal**: workhorse 作为受管 docker 服务与平台同网络,master 用服务名寻址(删 host.docker.internal 回连)

**Independent Test**: `docker compose --profile distributed up` 后 workhorse healthy,`docker exec dataweave-master wget -qO- http://workhorse:8300/health` → `{"ok":true}`,docker-compose 无 `host.docker.internal`/`extra_hosts` 残留

### Implementation for User Story 1

- [x] T005 [US1] `docker-compose.yml` 加 `workhorse` 服务:`profiles: ["distributed"]`、`build: ./deploy/workhorse`、`depends_on: dataweave-master(service_healthy)`、`environment: {WORKHORSE_AGENT_AUTH_BEARER_TOKEN: ${COMPANION_BRAIN_TOKEN:-}, WORKHORSE_AGENT_PROVIDERS_ANTHROPIC_API_KEY: ${DEEPSEEK_API_KEY:-}}`、`volumes: [./deploy/workhorse/mcp.json:/app/mcp.json:ro, ./deploy/workhorse/agents:/app/agents:ro]`、可选 `ports: ["8300:8300"]`
- [x] T006 [US1] `docker-compose.yml` 两 master(`dataweave-master` / `dataweave-master-2`):`COMPANION_BRAIN_BASE_URL` 改 `http://workhorse:8300`,**删 `extra_hosts: host.docker.internal:host-gateway`**
- [x] T007 [US1] 验证:`docker compose --profile distributed build workhorse` + `up -d`;workhorse healthy;容器内服务名寻址通;grep 确认 docker-compose 无 `host.docker.internal`

**Checkpoint**: US1 完成 —— 单命令起全栈(含 workhorse 受管服务),服务名寻址

---

## Phase 4: User Story 2 - sidecar 通信安全收紧 (Priority: P2)

**Goal**: 推理端点白名单来源 + bearer 双中间件;凭据 env 注入不出后端

**Independent Test**: `POST /v1/sessions` 无 Authorization → 401;有 Authorization 无 Origin → 403;两者齐 → 201;`grep` 镜像/代码库无明文 secret

**Depends on**: US1(白名单用容器服务名 origin)

### Implementation for User Story 2

- [x] T008 [US2] `backend/dataweave-master/src/main/java/com/dataweave/master/companion/infrastructure/WorkhorseBrainClient.java`:`Origin` 头从硬编码 `"null"` 改读 `@Value("${companion.brain.origin:}")`(空则不发头,本地 h2/IT 零影响),`jsonRequest()` 与 `streamTurn` 的 GET 两处
- [x] T009 [P] [US2] `docker-compose.yml` 两 master 加 `COMPANION_BRAIN_ORIGIN: http://dataweave-master:8000`(master-2 用 `:8200`)
- [x] T010 [US2] `deploy/workhorse/config.yaml` 与 `deploy/workhorse/config.runtime.yaml`(gitignored,serve-local 用):确认 `allowed_origins: [http://dataweave-master:8000, http://dataweave-master-2:8200]`,**删 `allow_null_origin: true`**
- [x] T011 [US2] 重打 backend:`mvnd -pl dataweave-api -am clean package -DskipTests -Dmaven.build.cache.enabled=false`(禁 build-cache 防 repackage 假绿)→ `docker compose --profile distributed build dataweave-master` → `up -d`(依赖 T008 代码改完)
- [x] T012 [US2] 验证:无 token 401 / 无 Origin 403 / 带过 201;审查镜像与代码库无可读 secret

**Checkpoint**: US2 完成 —— 未授权 100% 拒,凭据不出后端

---

## Phase 5: User Story 3 - 巡检基于平台真实数据 (Priority: P3)

**Goal**: workhorse 经 mcp 连后端 /mcp 加载 dataweave__*,巡检查真实数据

**Independent Test**: 触发一轮巡检,汇报引用真实对象(实例/节点/表),非 INFO「未完成」兜底;workhorse 日志见 dataweave server 加载 + 工具调用

**Depends on**: US1(容器化网络,workhorse 容器 → master /mcp 服务名)

### Implementation for User Story 3

- [x] T013 [US3] `deploy/workhorse/mcp.json`(gitignored)加 dataweave server:对照 `workhorse-agent init` 产物确认格式,填 `{type:http, url:http://dataweave-master:8000/mcp, headers:{Authorization:"Bearer dataweave-local-mcp-token"}}`;后端 `mcp.auth.token` 本地默认值,生产覆盖
- [x] T014 [US3] 验证:workhorse 日志见 `dataweave` MCP server 已加载;触发一轮巡检,workhorse 经 `dataweave__query_*` 查真实数据,汇报引用真实对象(非兜底);写动作经 PolicyEngine(发诱发写的消息验证审批/直执)

**Checkpoint**: US3 完成 —— 巡检基于真实数据

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: 降级、文档、全场景验证

- [ ] T015 验证降级(SC-005 / constitution IV ③):停 workhorse(`docker compose stop workhorse`),平台任务调度照常、运行态观测面板不报错、companion 优雅降级
- [ ] T016 [P] `deploy/workhorse/serve-local.sh` 顶部注释补两种部署形态说明(容器 = 标准/生产;裸机 serve-local.sh = 本地快速调试)
- [ ] T017 跑 `specs/072-companion-workhorse-prod/quickstart.md` 全 5 场景验证(P1 部署 / P2 收紧 / 对话 / P3 真实数据 / 降级)

---

## Dependencies & Execution Order

### Phase 依赖
- **Setup (Phase 1)**: 无依赖,立即开始
- **Foundational (Phase 2)**: 依赖 Setup;**阻塞所有 US**(T004 依赖 T003)
- **US1 (Phase 3)**: 依赖 Foundational —— **MVP**
- **US2 (Phase 4)**: 依赖 US1(白名单 origin 用容器服务名);T011 依赖 T008
- **US3 (Phase 5)**: 依赖 US1(容器网络连 /mcp)
- **Polish (Phase 6)**: 依赖各 US 完成

### User Story 依赖与并行
- **US1 (P1)**: Foundational 后即可,无跨 story 依赖 —— 先做(MVP)
- **US2 (P2)** / **US3 (P3)**: 均依赖 US1,但**彼此独立**(US2 改 client/config/compose,US3 改 mcp.json),可并行
- 各 story 独立可测(见 Independent Test)

### 并行机会
- T002 [P] 与 T001 并行(Setup)
- T009 [P](compose env)与 T008(client 代码)不同文件可并行(US2 内)
- T016 [P] 与 T015/T017 可并行(Polish)
- US2 与 US3 跨 story 并行(均依赖 US1,互不冲突)

---

## Implementation Strategy

### MVP First(仅 US1)
1. Phase 1 Setup(T001/T002)
2. Phase 2 Foundational(T003/T004)—— 容器化基础
3. Phase 3 US1(T005-T007)—— 单命令部署 + 服务名寻址
4. **STOP 验证**:workhorse healthy + 服务名寻址 + 无 host.docker.internal
5. (US1 即生产可部署的 MVP)

### Incremental Delivery
1. Setup + Foundational → 容器化基础
2. + US1 → 容器化部署(MVP)
3. + US2 → 安全收紧(白名单 + bearer,凭据不出后端)
4. + US3 → 巡检真实数据(mcp 接平台工具)
5. + Polish → 降级验证 + 文档 + 全场景

### Constitution 合规(贯穿)
- IV ① 凭据不出后端:所有 secret env 注入(T001/.env + T005/env + T003 占位)
- IV ② 写操作过 PolicyEngine:T014 验证写工具经闸门
- IV ③ 降级不阻塞:T015 验证

---

## Notes
- [P] = 不同文件无依赖可并行
- T011(重打 backend)务必 `-Dmaven.build.cache.enabled=false`,防 build-cache 跳 repackage 致假 fat jar(见 memory `mvnd-setsid-cache-false-green`)
- workhorse bin 在 docker build context(`./deploy/workhorse`),CI 无 bin 需先 fetch-bin.sh
- `dataweave-local-mcp-token` 本地默认值,生产须覆盖后端 `application.yml` `mcp.auth.token`
- 多租户 token 动态切换为非目标(见 spec Assumption)
