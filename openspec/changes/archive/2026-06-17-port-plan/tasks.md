## 1. 后端配置文件

- [x] 1.1 `backend/dataweave-api/src/main/resources/application.yml`: `server.port: 8080` → `8000`
- [x] 1.2 `backend/dataweave-worker/src/main/resources/application.yml`: `server.port: 8081` → `8100`
- [x] 1.3 `backend/dataweave-worker/src/main/resources/application.yml`: `dataweave.master.url: http://localhost:8080` → `http://localhost:8000`
- [x] 1.4 `backend/dataweave-api/src/main/resources/application.yml`: `agent.workhorse.base-url: http://127.0.0.1:8787` → `http://127.0.0.1:8300`（隐含 workhorse 端口改动）

## 2. 后端 Java 硬编码默认值

- [x] 2.1 `backend/dataweave-api/src/main/java/com/dataweave/api/infrastructure/CorsConfig.java` L20: allowedOrigins `http://localhost:3000` → `http://localhost:4000`；同步 Javadoc 注释
- [x] 2.2 `backend/dataweave-worker/src/main/java/com/dataweave/worker/interfaces/WorkerExecController.java` L48: `@Value("${dataweave.master.url:http://localhost:8080}")` → `8000`
- [x] 2.3 `backend/dataweave-worker/src/main/java/com/dataweave/worker/infrastructure/HeartbeatReporter.java` L32: `@Value("${dataweave.master.url:http://localhost:8080}")` → `8000`
- [x] 2.4 `backend/dataweave-worker/src/main/java/com/dataweave/worker/infrastructure/HeartbeatReporter.java` L39: `@Value("${server.port:8081}")` → `8100`
- [x] 2.5 `backend/dataweave-api/src/main/java/com/dataweave/api/infrastructure/DistributedTaskExecutionGateway.java` L33: `DEFAULT_WORKER_PORT = 8081` → `8100`；同步 Javadoc 注释里的 8081
- [x] 2.6 （隐含）`backend/dataweave-api/src/main/java/com/dataweave/api/application/bridge/WorkhorseHttpClient.java` L46: `@Value("${agent.workhorse.base-url:http://127.0.0.1:8787}")` → `8300`

## 3. 后端测试同步

- [x] 3.1 `backend/dataweave-api/src/test/java/com/dataweave/api/HealthAndCorsTest.java` L51/L57: `http://localhost:3000` → `http://localhost:4000`；方法名 `corsPreflightShouldAllowLocalhost3000` → `...4000`
- [x] 3.2 `backend/dataweave-worker/src/test/java/com/dataweave/worker/infrastructure/HeartbeatReporterTest.java`: 端口字面量按新分段对齐（8081→8100, 8082→8200, 9090→8300 或保留为显式 URL 形式）
- [x] 3.3 `backend/dataweave-api/src/test/java/com/dataweave/api/infrastructure/DistributedTaskExecutionGatewayTest.java`: 端口字面量 8081→8100、8082→8101

## 4. 前端

- [x] 4.1 `frontend/package.json`: `dev` 脚本改为 `next dev -p 4000`（或 `PORT=4000 next dev`）
- [x] 4.2 `frontend/components/agent-chat.tsx` L17: `NEXT_PUBLIC_AGENT_URL ?? "http://localhost:8080/agui"` → `http://localhost:8000/agui`
- [x] 4.3 （隐含）`frontend/next.config.ts` L5: `NEXT_PUBLIC_BACKEND_URL ?? "http://localhost:8080"` → `http://localhost:8000`（Next.js rewrite 代理后端 URL）

## 5. docker-compose.yml

- [x] 5.1 master-1 容器：`ports: "8080:8080"` → `"8000:8000"`；healthcheck URL `localhost:8080/api/health` → `localhost:8000/api/health`
- [x] 5.2 master-2 容器：`ports: "8082:8080"` → `"8200:8000"`
- [x] 5.3 worker-1 容器：`ports: "8081:8081"` → `"8100:8100"`；`DATAWEAVE_MASTER_URL: http://dataweave-master:8080` → `http://dataweave-master:8000`；注释 `master 按 http://<node-code>:8081 下发` → `:8100`
- [x] 5.4 worker-2 容器：`ports: "8083:8081"` → `"8101:8100"`；`DATAWEAVE_MASTER_URL` → `http://dataweave-master:8000`
- [x] 5.5 workhorse 服务：注释 `127.0.0.1:8787` → `127.0.0.1:8300`，`dataweave-api:8080/mcp` → `dataweave-api:8000/mcp`

## 6. 部署配置

- [x] 6.1 `deploy/workhorse/mcp.json` L8: `http://127.0.0.1:8080/mcp` → `http://127.0.0.1:8000/mcp`
- [x] 6.2 `deploy/workhorse/config.yaml`: 检查是否含 `8080`/`8787` 端口引用，有则同步

## 7. CLI (Go)

- [x] 7.1 `cli/main.go` L30: `DW_API` 默认 `http://localhost:8080` → `http://localhost:8000`
- [x] 7.2 `cli/main.go` L12 注释 + L291 help 文本：`8080` → `8000`

## 8. 文档同步

- [x] 8.1 `CLAUDE.md`: 所有 `8080`/`3000` 端口示例改为 `8000`/`4000`（Build & Run、Key Conventions、AG-UI 协议示例、Knowledge Base Navigation）
- [x] 8.2 `README.md`: 端口示例同步
- [x] 8.3 `docs/architecture.md`: L162 等端口示例同步
- [x] 8.4 `openspec/changes/task-core-capabilities/`: `design.md` L155、`tasks.md` L72 的 `localhost:3000` → `localhost:4000`
- [x] 8.6 （隐含）`openspec/changes/dataweave-managed-sidecar/`: proposal.md / design.md / specs/workhorse-supervisor/spec.md 中的 `8787` → `8300`
- [x] 8.5 `openspec/specs/deployment-ports/spec.md`: 归档后自动进入 base specs（本次变更归档时处理）

## 9. 验证

- [x] 9.1 后端编译：`cd backend && ./mvnw -q -pl dataweave-api,dataweave-worker -am compile` 零错误
- [x] 9.2 后端测试：`cd backend && ./mvnw -pl dataweave-api,dataweave-worker test` 全绿（含 HealthAndCorsTest / HeartbeatReporterTest / DistributedTaskExecutionGatewayTest）
- [x] 9.3 前端类型检查：`cd frontend && pnpm typecheck` 零错误
- [x] 9.4 端到端启动：`docker compose up -d`（PG/Redis/MinIO）+ `./mvnw -pl dataweave-api spring-boot:run`（起 8000）+ `pnpm dev`（起 4000）→ 浏览器访问 `http://localhost:4000`，确认 CopilotChat 渲染、AG-UI 消息能发能收、console 无 error
- [x] 9.5 浏览器验证产物截图存 `tmp/port-plan-workspace.png`，验证完清理
