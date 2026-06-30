# Research: 资产目录 / 指标市场前端收口（Phase 0）

> 本特性纯前端、对接稳定后端,无重大未知量。以下是已定档的设计决策（含 brainstorm + /speckit-clarify 结论）与对既有代码/端点的核实结论。

## D1. 写表单 / 确认载体 = shadcn Dialog

- **Decision**: 创建/编辑/上架表单与下线/下架/退订/对账确认,统一用既有 `components/ui/dialog.tsx`。
- **Rationale**: 用户在 brainstorm 阶段拍板 Dialog；shadcn `dialog` 已装,零新依赖；确认类天然适配模态。
- **Alternatives**: 右侧 Sheet 抽屉（更适合长表单但与现「右详情列」布局重叠）、详情列内联编辑（创建/编辑不一致）——均被否。

## D2. 反馈 = 沿用既有 setToast,不切 sonner

- **Decision**: 沿用两视图现有的 `const [toast,setToast]` + 固定底部右下角提示模式,不引入 `components/ui/sonner.tsx`。
- **Rationale**: 两视图当前一致用 setToast;为收口引入 toast 库属范围蔓延（YAGNI）。三态文案经此通道呈现。
- **风险/已知**: 现 asset-view 的 toast 用 `onAnimationEnd` 清除但无动画 → 可能不自动消失;收口时统一改为 `setTimeout` 定时清除（小修,顺带）。

## D3. 分页 = 既有 ui/pagination

- **Decision**: 用 `components/ui/pagination.tsx` 接 page 状态,替换写死的 `page=1/size=20`;显示当前页 + `result.total`;沿用后端 `truncated` 标识展示「结果已截断」。
- **Rationale**: 组件已存在;后端 search 已支持 page/size（asset `AssetSearchService`、metric `MetricListingService.search`）。
- **size**: 默认 20（与后端 default 一致）;不暴露 size 选择器（YAGNI）。

## D4. 选择器数据源（核实结论）

- **数据源选择器（创建资产 datasourceId）**: `GET /api/datasources`（`DatasourceController`），前端已有 `lib/datasource-api.ts`。
- **指标定义选择器（上架 metricId）**: `GET /api/metrics`（`MetricsController.list` → `List<MetricCard>{id,code,name,unit,versionNo,status,value}`,每 code 最新版本）。前端 `types.MetricCard` 已定义。
- **Decision**: 创建资产 Dialog 用 datasources 下拉;上架指标 Dialog 用 MetricCard 列表下拉（展示 code/name,提交 metricId+metricCode）。
- **Rationale**: 复用既有只读端点,无需新增 API。上架幂等（后端 `list()` 对已存在 listing 更新复位 LISTED）,故选择器展示全部定义即可,无需「仅未上架」过滤。

## D5. 写操作三态分流 = lib/gate-outcome.ts

- **Decision**: 抽一个纯函数 helper：输入 `ApiResponse<GateResult>` + i18n `t`,输出 `{ tone, message }`,把 `outcome=EXECUTED→done / PENDING_APPROVAL→pendingApproval / 其它或 code≠0→失败(取后端 message)` 归一,两视图复用。
- **Rationale**: FR-012/SC-005 硬要求三态如实;现 marketplace-view 已有内联 `handleGate`,asset-view 各处手写,抽出复用避免漂移、**杜绝把待审批显示成成功**。
- **列表刷新规则**: EXECUTED → 刷新列表/详情;PENDING_APPROVAL → 不乐观插入(条目要等审批通过才出现),仅 toast「待审批」;失败 → 不动列表。

## D6. 已知业务错误码 → 友好提示

- **Decision**: 在 helper 里对已知 `errorCode` 补更友好的本地化提示,未知码回落后端 `message`（信任后端,FR i18n 规则③）。已知码：
  - `catalog.duplicate_asset`（限定名重复,409）
  - `catalog.asset_invalid` / `catalog.listing_invalid`（必填缺失,400）
  - `catalog.reuse_cycle`（复用成环）→ 专门提示「会形成循环依赖」（FR-007）
  - `catalog.reuse_invalid`（consumerRef 空,400）
  - `catalog.not_certifiable`（DELISTED 不可认证,409）
  - `catalog.forbidden_sensitivity`（PII 无权,沿用服务端）
- **Rationale**: SC-006 要求已知拒绝场景给具体原因而非通用失败。前端只为这几个已知码补 key,不猜测未知码。
- **核实**: 这些码取自 `AssetCatalogService` / `MetricListingService` 的 `BizException`;前端 `ApiResponse.errorCode` 字段已存在（`catalog-api.ts` L16）。

## D7. PATCH 编辑语义 = 仅传改动字段

- **Decision**: 编辑 Dialog 预填详情,提交时**仅**把与初值不同的字段放入 patch（后端 `update()` 是 `containsKey` 判定：含键=改、缺键=不改）。`tags`/`glossaryTerms` 作为整体字段提交。
- **Rationale**: FR-002 部分更新语义;避免无意清空未触字段。
- **实现**: 表单维护 `initial` 快照,提交时 diff 出 dirty keys。

## D8. 对账（reconcile）UX

- **Decision**: 详情面板「对账」按钮 → 确认 → 调 `reconcileAsset` → 成功后重新 `fetchAsset` 回填新 status（ACTIVE/STALE）+ toast 说明判据（锚点缺失→STALE）。
- **核实**: 后端 `reconcile()` 判据=`lineageTableRef` 是否非空（neo4j 表节点物理校验是未来项）。故编辑表单**应允许填 `lineageTableRef`**,这样「填锚点 → 对账 → ACTIVE」「清锚点 → 对账 → STALE」闭环可在界面真跑（支撑 US1 场景5 / SC-001）。

## D9. 质量过滤（clarify 定档）

- **Decision**: 渲染质量分数下限输入,透传 `qualityMin` 给 `searchAssets`;控件旁常驻静态说明「质量数据来自 022 评分卡、当前环境可能为空」。**不**做动态空集探测。
- **Rationale**: /speckit-clarify Q2 → Option A;022 未落地,被动透传最简且可测。

## D10. 订阅载体（clarify 定档）

- **Decision**: 资产详情面板内联呈现「订阅/已订阅·退订」;资产目录头部「我的订阅」按钮 → `subscriptions-dialog` 聚合清单（`GET /api/subscriptions` → `listSubscriptions`）可退订。**不**新增顶层 ViewType。
- **Rationale**: /speckit-clarify Q1 → Option C。
- **核实**: `GET /api/subscriptions` 返回 `List<AssetSubscription>`(当前用户);`DELETE /api/subscriptions/{id}` 经闸门退订（属主校验在服务端）。详情内联订阅态需判断当前资产是否已在订阅列表 → 打开详情时可顺带比对 listSubscriptions 结果。

## 未决项

- 无 NEEDS CLARIFICATION 残留。所有 Technical Context 字段已确定。
