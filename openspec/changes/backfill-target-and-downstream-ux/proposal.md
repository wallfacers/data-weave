## Why

补数据当前从「空弹窗 + 手输数字 ID」出发:用户必须先去别处查到 task/workflow 的数字 ID,再回到弹窗 `placeholder="输入任务或工作流 ID"` 里粘贴,没人记得住这些 ID,操作起来完全不像成熟产品。更糟的是「包含下游」是个**摆设**——`includeDownstream` 存进 `backfill_run.include_downstream` 字段,但 `BackfillService` 触发时 `scope` 永远硬编码 `"FULL"`,勾不勾结果一模一样,等于在骗用户。本 change 把补数据从「以 ID 为中心」改成「以对象为中心」,并让「下游」真正有意义。

## What Changes

- **目标选择改造(核心)**:弹窗里的数字 ID 输入框 → 可搜索选择器,按名称搜索任务/任务流,每条候选展示 名称 + 路径 + 负责人 + 最近运行状态,选中后内部携带 ID,**数字 ID 对用户全程隐形**。
- **就地补数据入口**:在任务详情、血缘图节点、catalog 树节点上新增「补数据」入口,直接预填目标对象打开弹窗,不再从空弹窗起步。
- **去掉骗人的 downstream checkbox**:现有裸 `Checkbox 包含下游` 在「下游」真正生效前移除,避免继续误导;由下文「下游影响范围」交互取代。
- **让「下游」真正生效**:后端把 `includeDownstream` 接到 `WorkflowTriggerService` 既有的 `scope=DOWNSTREAM`(此前仅 `FULL`),并明确下游沿血缘(`LineageGraphService`)解析;前端打开目标后拉取血缘下游树,可视化展示「有哪些下游、几层」,用户勾选**具体补哪些**,看到本次补数据的影响范围。
- **复用既有能力**,不新造轮子:`LineageGraphService` / `/api/lineage/*`、`WorkflowTriggerService` 的 `scope=DOWNSTREAM/TO_NODE`、现有 task/workflow 列表数据源。

## Capabilities

### New Capabilities
- `backfill-target-selection`: 补数据目标的选择交互——可搜索目标选择器(名称/路径/负责人/最近运行状态)、就地补数据入口(任务详情/血缘图节点/catalog 树节点预填目标)、数字 ID 对用户隐形。

### Modified Capabilities
- `data-backfill`: `includeDownstream` 从无效占位变为真正生效——经 `scope=DOWNSTREAM` 沿血缘展开下游,并支持只补选定的下游子集;补数据请求体携带选定下游目标集合。

## Impact

- **前端**:`frontend/components/workspace/views/ops/backfill-dialog.tsx`(目标输入框 → 选择器;下游 checkbox → 血缘下游可视化勾选)、`backfill-panel.tsx`、`ops-alert-card.tsx`(就地入口预填沿用)、任务详情视图 / `lineage-graph.tsx` / `catalog-tree.tsx`(新增就地入口);i18n 双语键(`ops` 命名空间,新增搜索/下游相关文案,移除/替换旧 `backfillTargetIdPh` 等)。
- **后端**:`BackfillService`(接通 `scope=DOWNSTREAM`、下游子集解析)、`OpsController` `POST /api/ops/backfill` 请求体(新增下游目标集合字段)、可能新增「按名称搜索任务/工作流」与「目标血缘下游预览」查询端点;复用 `LineageGraphService` / `WorkflowTriggerService`。
- **数据**:`backfill_run` 既有 `include_downstream` 字段语义被激活;下游子集可能需要随实例落地标记(详见 design)。
- **闸门**:补数据仍走 `GatedActionService`,下游展开后影响面变大,`ActionRequest` 需携带受影响目标数量供策略分级参考(详见 design)。
- **测试**:`BackfillServiceTest` 新增 downstream scope 断言;前端选择器/就地入口的浏览器验证。
