# Quickstart: 血缘探索器入口重构（054）验证指南

端到端验证「搜索优先 + 跨库可辨 + 表/字段连线」。实现细节见 `data-model.md`/`contracts/`，本文只给可跑的验证路径与期望结果。

## 前置

- 后端：`cd backend && ./dev-install.sh && ./mvnw -pl dataweave-api spring-boot:run`（8000）。真 Neo4j 验证需 `etl-neo4j` 或 `NEO4J_TEST_URI`。
- 前端：`cd frontend && pnpm dev`（4000）。
- 血缘 seed 需含**跨数据源链**：`mysql.user → hive.dwd_user → hive.dws_user_1d → pg.rpt_user`，其中 hive 两表间有列级 `DERIVES_FROM`（如 `dwd_user.uid → dws_user_1d.user_id`）。IT seed 见 `dataweave-master/src/test/resources/lineage-test/seed-lineage.cypher`。

## 后端契约验证（真 Neo4j IT）

```bash
cd backend
setsid bash -c './mvnw -pl dataweave-master -am test -Dtest=LineageDatasourceProjectionIT \
  >/tmp/054-it.log 2>&1; echo $? >/tmp/054-it.exit' </dev/null >/dev/null 2>&1 & disown
# 轮询：[ -f /tmp/054-it.exit ] && echo "exit=$(cat /tmp/054-it.exit)" || { echo running; tail -1 /tmp/054-it.log; }
```

期望断言（对应 `contracts/` A/B/C）：
1. `neighborhood`/`upstream`/`downstream` 表节点 `attrs.datasourceId`+`datasourceName` 非空且与图库一致；同库共享。
2. `columns/lineage` 返回列级边（from/to=列 id），列节点带数据源（继承所属表）。
3. `search?q=user` 候选带 `datasourceName`，同名跨库表可区分。
4. METRIC 节点 attrs 不含 datasource。
5. 跨项目隔离：他项目资产零泄漏。
6.（若交付）`/datasources/{id}/tables` 只返回该库该项目的表。

## REST shape 契约（h2，无 Docker）

```bash
cd backend
setsid bash -c './mvnw -pl dataweave-api -am test -Dtest=LineageGraphEndpointTest \
  >/tmp/054-shape.log 2>&1; echo $? >/tmp/054-shape.exit' </dev/null >/dev/null 2>&1 & disown
```
期望：新字段/新端点包络 `{code,data}` 正确；既有端点字段**向后兼容不丢**；隔离回归绿。

## 手工 curl（快速看字段）

```bash
TOKEN=<Bearer>
# 节点是否带数据源
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8000/api/lineage/tables/<hiveTableId>/neighborhood?depth=2" \
  | jq '.data.nodes[] | {name, ds: .attrs.datasourceId, dsName: .attrs.datasourceName}'
# 搜索候选是否带数据源名
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8000/api/lineage/search?q=user" | jq '.data[] | {name, type, datasourceName}'
```
期望：跨库的表 `datasourceName` 各不相同；同名跨库表靠它区分。

## 前端单测（vitest）

```bash
cd frontend && pnpm vitest run lineage-layout
```
覆盖：跨源分类三态（intra/cross/unknown）；列级边产出带正确 `sourceHandle/targetHandle`；`datasourceColor` 确定性 + 配色耗尽以缩写兜底。

## 浏览器门（Playwright，沿用 052 登录绕过）

打开 `http://localhost:4000/?open=lineage`，验证：

1. **搜索优先入口**：首屏未锚定 → 搜索框居中/主位且聚焦，画布空态「搜一个资产开始」；输入 `dwd_user` 提交 → 候选带（数据源·类型·分层）→ 选中后图以该表为锚点加载，**全程未展开左侧任何数据源**。
2. **节点数据源徽标**：横跨 mysql/hive/pg 的图，每个表/列节点显所属数据源徽标（图标/名/色），同库共享。
3. **跨源边强调**：`mysql.user→hive.dwd_user`、`hive.dws→pg.rpt_user` 的边以跨源样式（异色）区别于 hive 库内边；左下图例含「跨源边/库内边/未知来源/数据源徽标」。
4. **表/字段连线**：展开 `dwd_user` 与 `dws_user_1d` 的列 → `dwd_user.uid` 行与 `dws_user_1d.user_id` 行之间有**连到具体列行**的连线；跨库字段映射走跨源样式；表→表连线仍在、与列级连线可区分。
5.（P3 若交付）左侧分面可在 数据源/分层/最近 间切换，「数据源」展开出真实的表（非占位跳级）。

## 回归（不回退）

052 双向/深度/原地展开/影响/路径/列级血缘行为不变（SC-006）——跑 052 既有 IT + 浏览器门 6/6 仍绿。

## 完成判据

- 后端 IT + shape 契约 + 隔离回归全绿；前端 vitest 绿；浏览器门 1–4（P1）实证通过。
- 无 `schema_version` 变更、无写侧/PG 改动、无 052 语义回退。
