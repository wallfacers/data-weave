# Quickstart: 容器化端到端验证

## 前置

- `.env`(项目根,gitignored)含:`COMPANION_BRAIN_TOKEN=<shared-secret>`、`DEEPSEEK_API_KEY=<deepseek-key>`、`COMPANION_BRAIN_ORIGIN=http://dataweave-master:8000`。
- `deploy/workhorse/bin/workhorse-agent-linux-amd64` 存在(否则先跑 `fetch-bin.sh`)。
- PG `schema_version = 0.21.0`(071 已灌,本 feature 不 bump)。

## 部署

```bash
docker compose --profile distributed build workhorse   # 构建 workhorse 镜像(新)
docker compose --profile distributed up -d             # 起全栈(含 workhorse 服务)
```

## 验证场景(对应 spec P1/P2/P3 + FR/SC)

### 1. 健康与拓扑(P1, SC-001)
- master `actuator/health` → `UP`;workhorse `/health` → `200`。
- master 经服务名寻址:`docker exec dataweave-master wget -qO- http://workhorse:8300/health` → `{"ok":true}`。
- 无 `host.docker.internal` / `extra_hosts` 残留(docker-compose 已删)。

### 2. 访问控制收紧(P2, SC-002/SC-003)
- `POST /v1/sessions`(无 Authorization)→ `401`。
- `POST /v1/sessions`(有 Authorization、无 Origin)→ `403 origin_forbidden`。
- `POST /v1/sessions`(有 Authorization + 白名单 Origin)→ `201`。
- 审查镜像/代码库:`grep -r "sk-\|<token>" deploy/workhorse/Dockerfile deploy/workhorse/config.yaml` 无明文 secret(凭据 env 注入)。

### 3. 对话推理(沿用 071)
三步法:`POST /v1/sessions` → `POST .../stream {user_message}` → `GET .../stream` 看 SSE `reasoning_delta` / `assistant_text_delta`(DeepSeek 真推理)。

### 4. 巡检真实数据(P3, SC-004,本 feature 新能力)
- 触发一轮巡检(四领域任一)。
- workhorse 经 mcp 调 `dataweave__query_*` 查后端真实数据。
- 汇报引用真实对象(实例 / 节点 / 表),**非** INFO「未完成」兜底。
- 验证 mcp 连通:workhorse 日志见 `dataweave` server 已加载 + 工具调用。

### 5. 降级不阻塞(constitution IV ③, SC-005)
- 停 workhorse(`docker compose stop workhorse`)。
- 平台任务调度照常(分钟任务正常触发/运行);运行态观测面板不报错。
- companion 调用优雅降级(巡检兜底 / 对话拒 `brain_unavailable`),不抛未捕获异常。

## 预期结果

| 场景 | 预期 |
|---|---|
| 单命令部署 | 全栈(含 workhorse 受管服务)起,无需手工启宿主进程 |
| 未授权访问 | 100% 拒(401/403) |
| 对话 | DeepSeek 推理流正常 |
| 巡检 | 基于真实数据汇报(非兜底) |
| sidecar 停 | 调度/观测照常(不阻塞) |
