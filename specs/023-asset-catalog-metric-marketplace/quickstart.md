# Quickstart 验证指南: 资产目录 + 指标市场

证明端到端工作。前置见各步;字段/契约见 [data-model.md](./data-model.md)、[contracts/](./contracts/)。

## 前置

- H2 profile 起后端(零外部依赖);**改 master 后先 `./dev-install.sh` 再跑 api**;长跑 `setsid`。
- 血缘相关场景需 neo4j(018 提供 driver);无 neo4j 时验"降级路径"。
- curl 带 `Authorization: Bearer`。

## 场景 1:编目与去重

1. `POST /api/catalog/assets`(datasource_id + qualified_name + owner + sensitivity)。
2. 同 `(datasource_id, qualified_name)` 再 POST → **断言** `catalog.duplicate_asset`(唯一约束)。
3. `GET /api/catalog/assets/{id}` → 展示 owner/描述/术语/标签/分级/schema。

## 场景 2:分面搜索

1. 造多条不同 owner/tag/sensitivity 资产。
2. `GET /api/catalog/assets?keyword=X&owner=A&sensitivity=INTERNAL&page=1` → **断言**返回交集、`facets` 计数正确、分页有界。
3. 超量造数据 → **断言** `truncated=true` 且日志有截断记录(不静默)。

## 场景 3:血缘消费 + 降级(SC-002 硬验收)

1. neo4j 在线、资产对应表有血缘 → `GET /api/catalog/assets/{id}/lineage` 返回上下游入口。
2. **停 neo4j** → 同请求返回 `code=catalog.lineage_degraded`、data=null;`GET /api/catalog/assets/{id}` **主功能照常**(元数据齐全)。前端隐藏血缘入口不报错。

## 场景 4:指标市场上架/详情/复用防环/认证

1. `POST /api/marketplace/metrics`(复用现有 atomic/derived 指标)→ 可被 `GET /api/marketplace/metrics` 搜到。
2. `GET /api/marketplace/metrics/{id}` → 定义 + 血缘(降级安全)+ owner + 新鲜度 + 认证状态。
3. `POST /metrics/{A}/reuse`(consumer=B),再 `POST /metrics/{B}/reuse`(consumer=A)→ **断言** `catalog.reuse_cycle`(防环)。
4. 以 agent 身份 `POST /metrics/{id}/certify` → **断言** `outcome=PENDING_APPROVAL`(L2,未认证),`agent_action` 审计;审批后 CERTIFIED + 徽章呈现。

## 场景 5:订阅 + ASSET_CHANGED 喂 021

1. `POST /api/catalog/subscriptions`(target=ASSET,change_filter=schema)。
2. 改该资产 schema(对账不一致)→ **断言**产生 `AlertSignal(ASSET_CHANGED)`(集成测试断言 publish);021 落地后据订阅通知。
3. 退订 → 再变更 → **断言**不通知。

## 场景 6:敏感度 + 租户可见性(SC-006)

1. 建 PII 资产(租户 A),用户无 PII 权 → **断言** `GET assets` 搜不到、详情 `catalog.forbidden_sensitivity`。
2. 租户 B 不可见租户 A 任何资产。

## 场景 7:写闸门零旁路

- 资产/上架/认证/订阅 agent 写经 `GatedActionService` + `agent_action` 审计;反证测试证明无旁路。

## 场景 8:STALE 失效

1. 资产对应底层表删/改名 → schema 对账 → **断言** status=STALE,UI 不展示可信绿灯。

## 前端验证

- `pnpm typecheck` 零错 + 双语 key 等集(catalog/marketplace 命名空间)。
- 浏览器:资产目录视图(左分面搜索/中列表/右详情含血缘入口+质量徽章+订阅按钮);指标市场视图(搜索/详情/复用/认证)。先核对 `catalog` 视图类型归属(任务目录 vs 数据资产),撞名则新建 `assets` 类型。

## 测试门禁(完成标准)

- 后端 `dataweave-master` + `dataweave-api` compile 0 错;单元(搜索/防环/降级/敏感度)+ 集成(WebTestClient 带 JWT,八场景)全绿;H2/PG 双库 DDL;`schema_version` 合并期三处恒等。
- 前端 typecheck 0 错;双语等集;浏览器验证。
- 写闸门零旁路反证;血缘/质量降级路径必测。
