## 1. Phase 1 — 目标选择器(纯前端,核心痛点)

- [x] 1.1 在 `backfill-dialog.tsx` 把目标数字 `<Input type="number">` 替换为可搜索选择器:`targetType` 切换 + 防抖关键词输入,按类型请求 `GET /api/tasks?keyword=&size=20` 或 `GET /api/workflows?keyword=&size=20`
- [x] 1.2 候选项渲染 名称 · catalog 路径 · 负责人;选中后内部持有 id、目标区显示名称;未选中时提交拦截并提示
- [x] 1.3 移除无效的「包含下游」`Checkbox`(及其 `includeDownstream` state),弹窗暂以「只补自身」提交
- [x] 1.4 i18n:`ops` 命名空间新增搜索/选择相关键(zh-CN + en-US 双 bundle 同步),移除/替换 `backfillTargetIdPh`、`backfillTargetId`、`backfillIncludeDownstream` 等不再使用的键
- [x] 1.5 在任务详情视图、`lineage-graph.tsx` 节点、`catalog-tree.tsx` 任务叶子挂「补数据」就地入口,复用 `BackfillDialog` 的 `initialTargetType/initialTargetId` 预填(血缘节点取表的产出 taskDefId)
- [~] 1.6 `pnpm typecheck` 通过(✓) + `pnpm build` 通过(✓);浏览器渲染门**未在本会话执行**(无 playwright MCP / 无 e2e harness),见结尾说明,待人工跑一次

## 2. Phase 2 后端 — 血缘下游解析与展开

- [x] 2.1 `LineageGraphService` 新增 `downstreamTasks(taskDefId)`:从目标产出表沿 `FlowEdge(fromTableId→toTableId, taskDefId)` BFS,收集读取这些表的下游任务,去重去自身
- [x] 2.2 `BackfillService.submitBackfill()` 接通下游:目标集合 = `[目标] ∪ downstreamTaskIds`,对每个 (task × bizDate) 调 `triggerBackfillTaskRun`;`downstreamTaskIds` 空/缺失时退化为只补自身(向后兼容)
- [x] 2.3 `BackfillRequest`(api dto + master OpsContracts) / `OpsController` 请求体新增 `downstreamTaskIds: List<Long>`,保留 `includeDownstream` 兼容字段(双方都加 6 参兼容构造)
- [x] 2.4 新增预览端点 `GET /api/ops/backfill/downstream-preview?targetType=task&targetId=`,返回下游任务列表(id/名称/类目节点/层级),内部走 2.1(经 DataOpsBridge)
- [x] 2.5 `OpsController` 构造 `ActionRequest` 时携带 `affectedTargetCount = 1 + downstreamTaskIds.size`,喂入 `GatedActionService`/`PolicyEngine`
- [x] 2.6 `BackfillServiceTest` 新增:选定下游子集生成对应实例数、空集合只补自身、下游解析受血缘约束(Mockito,10 测全绿)
- [x] 2.7 `compile` 零错误(✓ master+api);预览端点 + 提交端点经 `OpsDataCenterEndpointTest` 全栈验证(真 Spring 上下文 + H2 + JWT,18/18,含自建血缘链 e2e 断言),较裸 curl 更可靠

## 3. Phase 2 前端 — 下游影响范围可视化勾选

- [x] 3.1 弹窗选定目标后调 `downstream-preview`,以可勾选列表呈现下游(默认不全选,标注「依据现有血缘,可能不完整」)
- [x] 3.2 提交请求体改为携带 `downstreamTaskIds: number[]`(勾选结果);按 `outcome` 三态(EXECUTED/PENDING_APPROVAL/REJECTED)分流提示(沿用既有三态)
- [x] 3.3 i18n:下游预览/勾选/影响范围相关键双 bundle 同步(i18n:lint ✓)
- [x] 3.4 workflow 目标 M1 维持整 DAG(不开放下游子集),UI 隐藏其下游树(`targetType === "task" && target` 守卫)
- [~] 3.5 `pnpm typecheck` 通过(✓);浏览器渲染门**未在本会话执行**(同 1.6),后端下游路径已由 e2e 测试覆盖

## 4. 收尾

- [x] 4.1 本会话未产生浏览器验证产物;`tmp/` 既有文件为先前会话遗留且 gitignored,改动面干净
- [~] 4.2 勾选状态已更新;归档 `/opsx:archive` 待浏览器渲染门补跑后再执行
