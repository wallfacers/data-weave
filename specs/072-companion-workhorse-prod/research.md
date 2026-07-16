# Research: Companion Workhorse 生产收口

**Phase 0 输出**。spec 无 NEEDS CLARIFICATION(设计在 brainstorming 已充分澄清),本文件固化关键技术决策 + rationale + 备选。

## D1. workhorse 容器化镜像来源

- **决策**: `alpine` + `COPY` prebuilt 二进制(`deploy/workhorse/bin/workhorse-agent-linux-amd64`)。
- **rationale**: bin 现成(06-18 build ≥ 源码最新提交 06-17);镜像轻、构建快;无 go 编译工具链依赖。
- **备选(否决)**: ① multi-stage go build(重,bin 已够新不必重编);② workhorse 官方镜像(仓库无发布)。

## D2. secret 管理(凭据不出后端,constitution IV ①)

- **决策**: 所有敏感(auth bearer / DeepSeek key / mcp token)经环境变量注入,不进镜像/代码库;`config.yaml` 模板进镜像(占位 key,env 覆盖)。
- **rationale**: workhorse 原生支持 env 注入(`WORKHORSE_AGENT_AUTH_BEARER_TOKEN` / `WORKHORSE_AGENT_PROVIDERS_ANTHROPIC_API_KEY`,load.go 白名单);`.env` gitignored;镜像产物无敏感可公开审计。
- **备选(否决)**: 挂载含真 key 的 `config.runtime.yaml`(本地机特定、不通用、生产不可复制)。

## D3. origin 收紧策略

- **决策**: master 发 `Origin`(服务名 `http://dataweave-master:8000`,env `COMPANION_BRAIN_ORIGIN` 注入)+ workhorse `allowed_origins` 白名单;**删 `allow_null_origin`**。
- **rationale**: 显式白名单收紧 + bearer 双中间件(origin 防 CSRF / bearer 真认证);容器间 trusted network。
- **备选(否决)**: 保留 `allow_null_origin: true`(松,任何发 `Origin: null` 的未授权方可放行)。

## D4. mcp 平台工具接入

- **决策**: `mcp.json` 加 `dataweave` server(`url http://dataweave-master:8000/mcp` + `Authorization: Bearer <mcp.auth.token>`);workhorse 连后端 `/mcp` 加载 `dataweave__*`;tenant 由 mcp-token 绑定(=1),巡检 `project_id` 作工具参数。
- **rationale**: workhorse 是 MCP host(Streamable HTTP);后端 `/mcp` 已有 McpAuthFilter + TenantContext。
- **备选(否决)**: 无数据通道(巡检只能兜底 INFO「未完成」,不满足 P3)。
- **实现确认点**: `mcp.json` server 格式须对照 `workhorse-agent init` 产物确认(`type:http`/`url`/`headers`),Phase 1 实现时验证。

## D5. master → workhorse 寻址

- **决策**: 服务名 `http://workhorse:8300`(docker default network);删 `host.docker.internal` / `extra_hosts`。
- **rationale**: 容器化后同 network,服务名 DNS 解析;不再暴露宿主端口、无环境特定回连 hack。
- **备选(否决)**: `host.docker.internal`(联调临时方案,隔离弱、不可复制)。

## D6. 多租户局限(非目标)

- **决策**: 单租户(tenant=1,mcp-token 绑定);多租户 token 动态切换列为非目标。
- **rationale**: 本地/默认单租户;多租户需 per-tenant token 或运行时注入,scope 蔓延,后续 feature 处理。已在 spec Assumption + Edge Case 声明。

## D7. serve-local.sh 保留

- **决策**: 保留作本地裸机(非 docker)调试通道,不删。
- **rationale**: 容器化是标准/生产形态;裸机 serve-local.sh 便于改 config 快速重启调试。文档注明两种用途。
