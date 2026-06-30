# Quickstart: 验证血缘查询、API 与前端视图

**Feature**: 020-lineage-graph-api | **Date**: 2026-06-30

前提：018 已落地（neo4j docker-compose + driver `@Bean` + 只读会话 + 图模型约束 + greenfield 种子写入），019 已写入列级数据（缺失时列视图降级"仅表级"）。本特性只读消费。

---

## 0. 起服务

```bash
# neo4j + PG + Redis（018 的 docker-compose 含 neo4j 硬依赖）
cd backend && docker compose up -d
./dev-install.sh
./mvnw -pl dataweave-api spring-boot:run        # :8000
# 前端
cd frontend && pnpm install && pnpm dev          # :4000
```

---

## 1. 后端 API 验证（curl）

```bash
BASE=http://localhost:8000/api/lineage
TOKEN=...  # admin/admin 登录拿 JWT

# 结构下钻：库 → 表的列
curl -s "$BASE/datasources" -H "Authorization: Bearer $TOKEN" | jq '.data[] | {id,type,name}'
curl -s "$BASE/tables/<tableId>/columns" -H "Authorization: Bearer $TOKEN" | jq '.data[] | {name, attrs}'

# 任意深度下游（a→b→c→d 应返回 b,c,d）
curl -s "$BASE/tables/<aId>/downstream?depth=10&granularity=table" -H "Authorization: Bearer $TOKEN" | jq '.data.nodes[].name'

# 列级上游流
curl -s "$BASE/columns/<colId>/upstream" -H "Authorization: Bearer $TOKEN" | jq '.data | {granularity, edges}'

# 影响面（全下游可达，含表+列）
curl -s "$BASE/impact/<nodeId>" -H "Authorization: Bearer $TOKEN" | jq '.data | {nodeCount, truncated, downstream: [.downstream[].type]}'

# 指标血缘
curl -s "$BASE/metrics/<metricId>/lineage" -H "Authorization: Bearer $TOKEN" | jq '.data.sources[] | {type,name}'

# 运行态（可空）
curl -s "$BASE/sync-summary" -H "Authorization: Bearer $TOKEN" | jq '.data.syncedRows'
```

**期望**：
- downstream 返回与 018 写入一致的可达集合（SC-001）。
- 有界：超深/超广查询返回 `truncated:true` + `truncatedAt`，服务日志有 WARN 截断记录（SC-004）。
- 列级缺失表的 `/columns` 返回空数组。

## 2. neo4j 不可达降级（SC-003）

```bash
docker compose stop neo4j
curl -s "$BASE/datasources" -H "Authorization: Bearer $TOKEN" | jq '{code,message}'
#   期望 code = lineage.store_unavailable（zh/en 按 UI locale）
# 同时验证平台其余功能不受影响：
curl -s "http://localhost:8000/api/ops/metrics" -H "Authorization: Bearer $TOKEN" | jq '.code'   # 仍正常
docker compose start neo4j
```

## 3. 后端集成测试（Testcontainers neo4j）

```bash
# WSL2 必须脱离（CLAUDE.md 硬规则）
setsid bash -c 'cd backend && ./mvnw -pl dataweave-master test -Dtest=LineageQueryServiceNeo4jTest >/tmp/.../build.log 2>&1; echo $? >/tmp/.../build.exit' </dev/null >/dev/null 2>&1 & disown
# 单次轮询
[ -f /tmp/.../build.exit ] && echo "DONE exit=$(cat /tmp/.../build.exit)" || tail -1 /tmp/.../build.log
```

覆盖：多跳上下游正确集合、影响面闭包、列级 DERIVES_FROM 流、库/表/列分层下钻、截断边界、租户隔离（跨租户不泄漏）、不可达降级。

## 4. 前端血缘视图（浏览器验证）

1. admin/admin 登录拿 JWT 注入 `localStorage['dw.auth.token']`；reload。
2. 深链 `http://localhost:4000/?open=lineage`。
3. 验证：
   - 左侧库→表→列三级树可逐级展开/折叠（hugeicons 展开图标）。
   - 选中表 → 右侧画布渲染表级血缘流；切列粒度 → 列级流可视（US1）。
   - 选中节点 → 影响面下游高亮（语义 token，无手写 dark:）（US2）。
   - 指标叠加徽标 + 今日同步行数（null 时显示"估算中"）（US3）。
   - 停 neo4j → 视图友好降级提示（错误码 message），workspace 其余 tab 不受影响（SC-003）。
4. `cd frontend && pnpm typecheck` 零错；双语 key 等集（切 en-US 文案齐全）。

## 5. 完成判据

- [ ] SC-001 任意深度上下游/影响面返回正确可达集合（Testcontainers 绿）。
- [ ] SC-002 库/表/列三级下钻 + 列级流可视。
- [ ] SC-003 neo4j 不可达返回 `lineage.store_unavailable`，平台其余 100% 不受影响。
- [ ] SC-004 有界 + 分页 + 截断有日志。
- [ ] SC-005 `pnpm typecheck` 零错 + 双语 key 等集 + 浏览器渲染正确。
