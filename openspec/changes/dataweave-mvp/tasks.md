## 1. 项目骨架与工具

- [x] 1.1 建立 `frontend/` `backend/` `docs/` 目录与共享 `openspec/`
- [x] 1.2 全局安装 OpenSpec 并 `openspec init`（claude 工具）
- [x] 1.3 写 `docs/architecture.md` 架构真相源
- [x] 1.4 根 `README.md` 说明两项目布局与运行方式
- [x] 1.5 `docker-compose.yml`：PostgreSQL + Redis（生产态可选）

## 2. 后端骨架（Spring Boot 4 + Java 25，Maven 四模块）

- [x] 2.1 父 pom：Spring Boot 4.0、Java 25、四模块聚合、依赖版本管理
- [x] 2.2 `dataweave-api` 模块：WebFlux、AG-UI SSE 端点、DDD 四层包结构
- [x] 2.3 `dataweave-master` 模块：任务/调度/指标/血缘领域模型、DDD 四层
- [x] 2.4 `dataweave-worker` 模块：执行器基类 + Shell 执行器骨架
- [x] 2.5 `dataweave-alert` 模块：规则实体 + 通知通道接口骨架
- [x] 2.6 `WebClientConfig`：自建 `@Bean WebClient.Builder`（SB4 必需）
- [x] 2.7 H2 + `schema.sql`（兼容 PG 语法）+ `data.sql` 种子（GMV 指标、每日 GMV 任务、mock orders）
- [x] 2.8 application.yml：默认 H2 profile + `pg` profile（PostgreSQL/Redis）
- [x] 2.9 Maven Wrapper（`mvnw`），确认 `mvnw spring-boot:run` 起得来

## 3. Agent 引擎（mock）与领域服务

- [x] 3.1 `LlmClient` 接口 + 默认 mock 实现（预留真模型替换点）
- [x] 3.2 意图路由器：指标查询 / Text-to-SQL / 建任务 / 血缘问答 / 兜底
- [x] 3.3 Text-to-SQL：预置问法 → SQL，只读校验后在库上执行返回表格
- [x] 3.4 指标服务：按名查指标、执行口径 SQL、返回数据 + 口径溯源
- [x] 3.5 任务服务：自然语言 → 任务定义 + cron + 上线 + mock 实例推进
- [x] 3.6 血缘服务：查 `metric_lineage` 返回「指标 → SQL → 物理表」
- [x] 3.7 AG-UI 事件编排：把领域结果转为文本增量 + 结构化结果事件，SSE 输出

## 4. 前端骨架（Next.js + shadcn + CopilotKit v2）

- [x] 4.1 shadcn init（preset `b5xwED9co`，template next）—— Next 16 + React 19，主题与 preset 完全一致
- [x] 4.2 `frontend/DESIGN.md`：收录 oklch 亮/暗主题 + rounded + chart 色板（@google/design.md 格式，lint 通过）
- [x] 4.3 `@google/design.md` 装为 devDep + `design:lint`/`design:export` 脚本；globals.css 由 preset 生成（取值与 DESIGN.md 一致）
- [x] 4.4 `next-themes` 亮/暗切换（preset 自带，按 `D` 键切换）
- [x] 4.5 安装 CopilotKit v2（1.59.5）+ `@ag-ui/client`（对齐 0.0.53），`/agent` 页 `CopilotKitProvider` + `CopilotChat` 直连 `NEXT_PUBLIC_AGENT_URL`
- [ ] 4.6 表格富渲染：CUSTOM 事件（dataweave.result/fleet…，凡含 columns+rows）→ shadcn `Table` 富渲染。**代码已就位并提交**：`components/agent/result-table.tsx` + `agent-chat.tsx` onCustomEvent 接线（最近 N 条、可关闭）；`pnpm typecheck` 零错误；后端契约实证（mock+h2 对 Text-to-SQL 真发 `dataweave.result{kind:table,columns:["?column?"],rows:[{"?column?":7}]}`）。阻塞已解除（i18n next-intl 修复后 app 恢复，chat 正常加载）。**剩 Browser Gate**：发一条 Text-to-SQL 实跑确认 ResultTable 卡片渲染（快速可补）
- [x] 4.7 基础布局：sidebar 应用外壳 + 导航（概览/Agent/任务/指标/血缘）+ 概览页 + 占位页

## 5. 端到端验证

- [x] 5.1 后端：`mvnw spring-boot:run` 起容器、AG-UI 端点返回 SSE
- [x] 5.2 前端：`pnpm dev` 打开 `/agent`，真在浏览器跑一次（build 过 ≠ 能渲染）
- [x] 5.3 走通 5 条 MVP：对话 / Text-to-SQL / 指标查询 / 建任务 / 血缘问答
- [x] 5.4 亮/暗主题切换可用
