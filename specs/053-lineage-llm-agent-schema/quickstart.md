# Quickstart & Validation: 血缘 AI Agent 通道 + 数据源 Schema 解析

端到端验证四个用户故事。前置：后端跑起（`./dev-install.sh` 后 `./mvnw -pl dataweave-api spring-boot:run`，JDK25 + 默认 PG profile 或 `-Dspring-boot.run.profiles=h2`），neo4j 经 `etl-neo4j` 就绪，前端 `pnpm dev`。

> 详细字段/契约见 [contracts/](contracts/) 与 [data-model.md](data-model.md)，此处只列可运行验证步。

## US2（P2）数据源实时 Schema 解析 `SELECT *`

最独立、无需外部 AI，先验。

1. 建一个数据源指向一个真实库（其中有 `user` 表，列**未** seed 进 neo4j 列目录）。
2. 建一个 SQL 任务，绑定该数据源，content=`INSERT INTO dw.snap SELECT * FROM user`，push/上线。
3. **预期**：血缘图谱中 `dw.snap` 的字段级血缘按 `user` 的真实列逐列建立（非整体降级）；neo4j `(:Table{qualifiedName:'user'})-[:HAS_COLUMN]->(:Column)` 出现回填的列（带 `dataType`/`ordinal`）。
4. 断连数据源后重跑同任务 → 列级降级但表级血缘与 push 均正常（FR-013）。

**IT**：`DatasourceSchemaResolverIT`（直连 H2/PG 取列）+ neo4j IT 验回填。

## US1（P1）云 AI Agent 抽取通道（异步）

1. `PUT /api/lineage/agent-config`（带 JWT + projectId）：
   ```json
   {"protocol":"OPENAI","baseUrl":"<兼容端点>","model":"<模型>","apiKey":"<key>","enabled":true}
   ```
   `POST /api/lineage/agent-config/test` 返回 `ok:true`。
2. push 一个规则/内嵌 SQL 均不覆盖、但语义上有写表意图的 Python 任务（如通过某私有接口写 `dw.result`）。
3. **预期**：push **立即返回**；短时（异步）后血缘图谱出现来源 `SCRIPT_AGENT` 的写边（带置信度）；`lineage_agent_call` 有一条 `status=SUCCESS`。
4. 把配置 `protocol` 改 `ANTHROPIC` 指向 Anthropic-兼容端点，重 push 同任务 → 抽取的表/方向语义一致（FR-002）。
5. 造一个 Calcite 因方言解析失败的 SQL 任务 → 异步 AI 兜底出边；造一个 Calcite 正常解析的 SQL → **无** AI 外呼（`lineage_agent_call` 无新行，FR-001/D7）。

**验证异步不阻塞**：push 响应耗时不含 AI 外呼时长（对比开/关配置的 push 延迟）。

## US3（P3）Schema 接地抑制幻觉 + 缓存回填

1. 对一个源表列真实存在但 neo4j 未登记的脚本任务，开启 AI + US2 已生效。
2. push → AI 产出的字段边**全部落在真实列集合内**（无越界幻觉列；越界列在 `lineage_agent_call.status=REJECTED` 留痕）。
3. 再次 push 引用同表的任务 → 日志/审计显示列元数据命中缓存（进程缓存或 neo4j），**未重复连库**（SC-006）。
4. 改源库该表结构后重 push → 缓存 evict，血缘反映新 schema（FR-018）。

## US4（P3）治理护栏

1. 未开启 AI 的项目 push 脚本 → `lineage_agent_call` 零新增（SC-005）。
2. `GET /api/lineage/agent-config` → `apiKeyMasked` 形如 `sk-…a1b2`，全链路日志 grep 不到明文 key。
3. AI 外呼超时（把端点指向不可达）→ push 仍在预算内成功，AI 边不出现，`status=DEGRADED` 留痕，确定性血缘不受影响。

## 回归与门禁

- `cd backend && ./mvnw -q -pl dataweave-master compile`（每次编辑后）
- neo4j IT 直连真验（勿只靠 mock——见记忆「后端字段没产出→前端死掉」教训）
- H2 + PG 双方言各跑一遍 schema + resolver IT
- `cd frontend && pnpm typecheck` + Playwright 浏览器门验配置表单（脱敏、启用开关、test 按钮）
- schema_version：文件头 + DB 行 + 项目版本三处一致升至 `0.11.0`
