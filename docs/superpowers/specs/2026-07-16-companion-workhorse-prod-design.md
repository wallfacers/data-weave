# Companion Workhorse 生产收口设计

- **日期**: 2026-07-16
- **状态**: approved
- **baseline**: main `ad089069`(071 真 workhorse 接入 + bearer 认证已落地)

## 背景

071 companion 真 workhorse 已接入 main(`ad089069`):workhorse 作为**宿主 sidecar 进程**跑 8300(DeepSeek 推理,bearer 认证),docker 内 master 经 `host.docker.internal` 回连。本地联调全链路通过,但三项生产遗留待收口:

1. workhorse 跑在宿主(非容器),master 经 `host.docker.internal` 回连 —— 容器隔离弱、部署不可复制、宿主端口暴露。
2. `allow_null_origin: true` —— Origin 校验松(任何发 `Origin: null` 的请求放行,非真认证)。
3. `mcp.json` 空 servers —— 巡检无 `dataweave__*` 平台只读工具,只能兜底产 INFO「未完成」汇报,查不到真实数据。

## 目标

三项生产收口,全部落地到 `docker-compose.yml` / `Dockerfile` / 配置,本地 `docker compose up` 容器化验证 companion 对话 + 巡检(用 `dataweave__*` 查后端真实数据)全链路跑通,提交。

非目标:多租户 mcp token 动态切换(已知局限,后续)、workhorse 源码改动(用 prebuilt bin)。

## 技术约束(已探查)

- workhorse **无现成 Dockerfile/镜像** → 自写。
- `auth.bearer_token` 可 env 注入:`WORKHORSE_AGENT_AUTH_BEARER_TOKEN`(`internal/config/load.go:146`)。
- DeepSeek `api_key` 可 env 注入:`WORKHORSE_AGENT_PROVIDERS_ANTHROPIC_API_KEY`(env 白名单);**`base_url`/`model` 不能 env 改**,须在 config 文件(memory: `workhorse-config-loading-ignores-local-and-anthropic-env`)。
- 后端 `/mcp` Bearer token = `mcp.auth.token`(`application.yml`,默认 `dataweave-local-mcp-token`),token 绑定 tenant=1/user=1(`McpAuthFilter`)。
- workhorse agents 走 `agents.dir`(config);mcp 走 `mcp.config_path` 引用外部 `mcp.json`。
- 配置加载四层:Default → 单个 `--config` yaml → `WORKHORSE_AGENT_*` env → CLI flag。

## 设计

### 1. 架构 / 网络拓扑

workhorse 作为 `docker-compose.yml` 的 `distributed` profile 服务,与 master/worker 同 default network:

```
[docker default network]
  dataweave-master ──http://workhorse:8300──▶ workhorse ──https──▶ api.deepseek.com
       │                                          │
       └──(master /mcp)◀── MCP(dataweave__*)──────┘
```

- master 改 `COMPANION_BRAIN_BASE_URL: http://workhorse:8300`(服务名),**删 `host.docker.internal` / `extra_hosts`**(③ 收口)。
- workhorse `ports: 8300` 可选暴露(宿主调试访问,非必需)。

### 2. workhorse 镜像(`deploy/workhorse/Dockerfile`)

```dockerfile
FROM alpine:3
RUN apk add --no-cache bash ca-certificates
WORKDIR /app
COPY bin/workhorse-agent-linux-amd64 /app/workhorse-agent
COPY config.yaml /app/config.yaml          # 模板,无敏感(占位 key,env 覆盖)
COPY mcp.json /app/mcp.json
COPY agents /app/agents
RUN chmod +x /app/workhorse-agent
EXPOSE 8300
ENTRYPOINT ["/app/workhorse-agent"]
CMD ["serve", "--config", "/app/config.yaml", "--host", "0.0.0.0", "--port", "8300"]
```

- build context = `./deploy/workhorse`(含 prebuilt bin,本地有;CI 无 bin 需先 `fetch-bin.sh`)。
- `config.yaml` 模板进镜像(无敏感),与 `config.runtime.yaml`(含真 key,gitignored,本地 `serve-local.sh` 用)分离。

### 3. secret 全走 env(不进镜像/git)

| secret | env | 来源 |
|---|---|---|
| workhorse auth bearer | `WORKHORSE_AGENT_AUTH_BEARER_TOKEN` | `${COMPANION_BRAIN_TOKEN}`(.env) |
| DeepSeek api key | `WORKHORSE_AGENT_PROVIDERS_ANTHROPIC_API_KEY` | `${DEEPSEEK_API_KEY}`(.env,新增) |
| mcp token | mcp.json `headers.Authorization` | `dataweave-local-mcp-token`(后端 `mcp.auth.token`,本地默认;mcp.json gitignored) |

- `config.yaml` 模板:`base_url: https://api.deepseek.com/anthropic`、`models.default: anthropic:deepseek-v4-pro`(非敏感,字面量);`api_key` 占位(`REPLACE`),env 覆盖。
- `.env`(gitignored)加 `DEEPSEEK_API_KEY=<deepseek-key>`(与现有 `COMPANION_BRAIN_TOKEN` 同管理)。

### 4. origin 白名单收紧(替代 `allow_null_origin`)

- master 发 `Origin: http://dataweave-master:8000`(新 env `COMPANION_BRAIN_ORIGIN` 注入,默认服务名)。
- `WorkhorseBrainClient`:`.header("Origin", "null")` → 读 `${companion.brain.origin:http://dataweave-master:8000}`(空则不发,本地 h2/IT 无 origin 场景零影响)。
- workhorse `config.yaml`:`allowed_origins: [http://dataweave-master:8000, http://dataweave-master-2:8200]`,**删 `allow_null_origin`**。
- docker-compose 两 master 加 `COMPANION_BRAIN_ORIGIN: http://dataweave-master:8000`(master-2 用 `:8200`)。

### 5. mcp 补 `dataweave__` 平台工具

`mcp.json`(实现时对照 `workhorse-agent init` 产物确认 server 格式,预期 standard MCP):

```json
{
  "mcpServers": {
    "dataweave": {
      "type": "http",
      "url": "http://dataweave-master:8000/mcp",
      "headers": { "Authorization": "Bearer dataweave-local-mcp-token" }
    }
  }
}
```

- workhorse 启动连后端 `/mcp`,加载 `dataweave__query_*`/`instance_logs` 等只读工具。
- tenant 由 mcp-token 绑定(=1);巡检 `project_id` 作为工具参数传(`WorkhorseBrainClient.buildPatrolPrompt` 已注入)。
- 后端 `mcp.auth.token` 本地用默认值,生产覆盖 `application.yml`/env。

### 6. serve-local.sh 保留

作本地裸机(非 docker)快速调试备选,不删。文档注明:标准/生产走 docker compose,裸机调试走 serve-local.sh。

## 验证

1. `docker compose --profile distributed build workhorse`(新镜像)。
2. `docker compose --profile distributed up -d`(含 workhorse,master 去 host.docker.internal)。
3. master healthy + SchemaVersionGuard 通过;workhorse `/health` 200。
4. companion 对话:前端/三步法发消息,DeepSeek 推理流(`assistant_text_delta`)正常。
5. companion 巡检:触发一轮巡检,workhorse 经 mcp 调 `dataweave__query_*` 查后端真实数据,产出结构化汇报(非 INFO 兜底)。
6. 容器内 `curl http://workhorse:8300/health` 通;`/v1/sessions` 无 token 401、带 token 201、无 Origin 403。

## 已知局限(非本次)

- mcp-token 绑定 tenant=1;多租户生产需 per-tenant token 或动态切换机制(后续)。
- workhorse bin 进镜像依赖 build context 含 bin;CI/纯净环境需先 `deploy/workhorse/bin/fetch-bin.sh`。
- `dataweave-local-mcp-token` 本地默认值,生产必须在 `application.yml`/env 覆盖。

## 文件清单

**新增**:
- `deploy/workhorse/Dockerfile`
- `deploy/workhorse/config.yaml`(模板,无敏感,进 git)

**改**:
- `docker-compose.yml`:加 `workhorse` 服务(distributed);两 master `COMPANION_BRAIN_BASE_URL` 改 `http://workhorse:8300`、删 `extra_hosts`、加 `COMPANION_BRAIN_ORIGIN`。
- `backend/.../WorkhorseBrainClient.java`:`Origin` 读 `companion.brain.origin`(env)。
- `deploy/workhorse/config.runtime.yaml`(gitignored,本地 serve-local 用):`allowed_origins` + 去 `allow_null_origin`(保持与镜像 config.yaml 模板一致)。
- `deploy/workhorse/mcp.json`(gitignored):加 `dataweave` server。
- `.env`(gitignored):加 `DEEPSEEK_API_KEY`、`COMPANION_BRAIN_ORIGIN`(可选)。
