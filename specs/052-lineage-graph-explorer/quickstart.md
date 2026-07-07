# Quickstart: Lineage Graph Explorer 验证指南

端到端验证血缘图探索器（前端富化 + 后端查询补齐）。实现细节见 `tasks.md`；契约见 `contracts/lineage-query-api.md`；数据模型见 `data-model.md`。

## 前置

```bash
# 后端（PostgreSQL + Redis + Neo4j 血缘）
cd backend && docker compose up -d
./dev-install.sh                                  # 改后端后必先装 m2 再跑 api
./mvnw -pl dataweave-api spring-boot:run          # :8000
# 前端
cd frontend && pnpm install && pnpm dev           # :4000  → http://localhost:4000/?open=lineage
```

血缘数据在 Neo4j（唯一存储）。若无种子，用 `backend/dataweave-master/src/test/resources/lineage-test/seed-lineage.cypher` 灌一份（含 a→b→c→d FLOWS_TO 链、列级 DERIVES_FROM、SYNCED、tenant2 隔离）。

## 后端查询验证（真 Neo4j IT）

```bash
# 长跑必须 setsid 脱离（WSL2 硬规则）
setsid bash -c 'cd backend && ./mvnw -pl dataweave-master -am test \
  -Dtest="*Lineage*IT,LineageSeamE2EIT" >build.log 2>&1; echo $? >build.exit' </dev/null >/dev/null 2>&1 & disown
# 轮询
[ -f build.exit ] && echo "DONE exit=$(cat build.exit)" || { echo running; tail -1 build.log; }
```

**期望覆盖**（对应 contracts）：
- `search` 中缀命中（`order_detail` → dwd_order_detail），跨项目零泄漏（不返回 tenant2）。
- `impact` 返回 `edges`（闭合于 downstream、REMOVED 剔除）、`reachableTotal` 与分页解耦、达 cap 时 `totalIsLowerBound=true`。
- `neighborhood` 双向带边（上游∪下游 + 连接边，distinct 去环）。
- `paths` 多条 A→B 路径去重高亮集；无路径 `pathExists=false`。
- 节点 `attrs` 富化 `layer/producers/syncedRowsToday/lastSyncDate`（需种子补 `:Task-[:WRITES]->:Table`）。

REST shape + 隔离契约（h2，免容器）：`./mvnw -pl dataweave-api test -Dtest=LineageGraphEndpointTest -Dspring-boot.run.profiles=h2`。

### 手工 curl（带 JWT）

```bash
# 搜索
curl -s "http://localhost:8000/api/lineage/search?q=order_detail" -H "Authorization: Bearer $DW_TOKEN" | jq '.data'
# 影响分析（看 edges 非空 + reachableTotal）
curl -s "http://localhost:8000/api/lineage/impact/<nodeId>?depth=10" -H "Authorization: Bearer $DW_TOKEN" | jq '.data | {reachableTotal,totalIsLowerBound, edges:(.edges|length)}'
# 双向邻域（看 edges 非空）
curl -s "http://localhost:8000/api/lineage/tables/<id>/neighborhood?depth=2" -H "Authorization: Bearer $DW_TOKEN" | jq '.data.edges|length'
# 路径高亮
curl -s "http://localhost:8000/api/lineage/paths?from=<A>&to=<B>&depth=10" -H "Authorization: Bearer $DW_TOKEN" | jq '.data | {pathExists, nodes:(.nodes|length), edges:(.edges|length)}'
```

## 前端验证（Playwright 浏览器门）

`http://localhost:4000/?open=lineage`，逐条对照验收：

| 场景 | 期望（Spec 映射） |
|---|---|
| 打开某表血缘 | 三栏布局：左 catalog 树 · 中 `@xyflow/react` 画布（非手绘 SVG）· 详情为**嵌入画布的可关闭面板** |
| 布局 | 上游左→下游右分层（dagre），非固定 4 列网格；缩放/平移/自适应视野/小地图可用（US1/FR-001/002） |
| 方向 | Segmented 切 上游/下游/双向，图与边随之变；双向所有连边完整（FR-003/007/SC-002） |
| 深度 | Stepper 调深度，当前值可见（FR-004） |
| 原地展开 | 点下游节点 → 邻居**追加**、原节点与视图保留；再点收起（FR-005/SC-004） |
| 选中 | 高亮直接连边 + 嵌入面板显节点属性（层/任务/新鲜度/synced，FR-006/019） |
| 搜索 | 输名字片段 → 候选带层/类型标注 → 选中聚焦居中（US2/FR-008~010） |
| 影响分析 | 高亮受影响节点**及路径边** + 「受影响 N 个」真实计数（US3/FR-012/013/SC-003） |
| 路径高亮 | 选 A、B → 高亮所有连接路径；无路径提示（FR-014） |
| 列级 | 表节点展开列 + 列间派生边 + 边样式编码可信度/来源 + 图例（US4/FR-015/016/SC-006/007） |
| 深链/导出 | 复制深链新开恢复锚点/方向/深度；导出当前子图（US5/FR-020/021/SC-008） |
| 降级/截断 | 存储不可用给降级提示；超上限显式截断提示（FR-023/024/SC-010） |

前端门禁：`pnpm typecheck` 零错；`pnpm design:lint`（改了 DESIGN.md 需过）。

## reuse-first 自查（交付前）

- [ ] 画布复用 `DagRenderer` / `FlowCanvasWithPanel`，**无任何手绘 SVG 图**
- [ ] 详情走 `DetailPanelShell`（headerExtra Tab：节点/边/影响）
- [ ] 滚动 `DwScroll`、下拉 `DropdownSelect`、搜索 `Input`、加载 `LoadingState`、刷新 `ViewRefreshControl`
- [ ] 新 `Segmented`/`Stepper` 已回填 `DESIGN.md` 原语速查表 + 深章节
- [ ] 图标 hugeicons、语义 token、无 `dark:` 手写、无区域分割线
- [ ] i18n 新键 `lineageView` 双 bundle 对齐（`pnpm` i18n 校验）
- [ ] 后端无 PG schema 改动、`schema_version` 未 bump；查询全只读未触 PolicyEngine
