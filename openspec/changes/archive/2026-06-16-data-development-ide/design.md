## Context

当前"任务开发"能力散落三处且割裂：`sql-workbench`（写死样例、不存盘的 Monaco 工作台）、`task-flow`（实例运维表 + 侧栏任务编辑入口）、侧栏 `TaskEditPanel`（用 `<textarea>` 写脚本、无语法高亮）。而"工作流编排"视图（`workflow-canvas-view.tsx`）已经是"左类目树 + 右画布"的雏形——类目树已内嵌、Monaco（`components/code-editor.tsx`）已存在但只被废样例工作台用着。

后端侧：`TaskController`/`WorkflowController` 只有 CRUD + publish + offline + preview-params；`OpsController` 的运行类端点（pause/resume/kill/rerun/recover）全部作用于**已存在实例**。没有任何"从定义立即起一个实例"的 REST 入口——实例只能由调度器（cron/依赖）凭空生成。关键现状（已 grep 核实）：
- `workflow_instance` **已有** `trigger_type VARCHAR(32)` 列（schema.sql:400），且 `WorkflowTriggerService.trigger(wf, triggerType, bizDate, priorityOverride)`（:52）已能传 `"MANUAL"` 并 `setTriggerType`，测试里早在用——**工作流 /run 几乎零后端工作量，薄包装它即可**。
- `task_instance`（schema.sql:417/425）**没有** `trigger_type`，只有 `run_mode`（NORMAL/TEST）。"正式/不计统计"靠 `run_mode=NORMAL` 判定（OpsService、WorkflowStateService 过滤 NORMAL）。
- `task-test-run` 规范（`run_mode=TEST` 草稿沙箱）目前**只有 spec、未落地**：`TaskController` 无任何 run/test-run 端点。本变更 MUST NOT 把它当"草稿验证兜底路径"引用——那是空指。

本变更把"工作流编排"升格为 DataWorks 式"数据开发"IDE：左树常驻、右侧内层子 Tab（画布/编辑），写完即跑、就地看日志与 DAG 运行态。

## Goals / Non-Goals

**Goals:**
- 一个视图内闭环：组织（树）→ 编辑（Monaco 子 Tab）→ 跑（手动触发正式实例）→ 观测（日志流 + DAG 节点变色）。
- 内层子 Tab 自管，风格与顶层 `TabStrip` 一致；左树跨子 Tab 常驻。
- 类目树：本地搜索 + 叶子 CRUD + 缩进对齐 + 去拖拽提示文案。
- 后端补 `POST /run`（任务 + 工作流）手动触发**正式实例**，经闸门 + 审计。
- 消除原生 `window.prompt`，引入 base 风格 `Dialog`。

**Non-Goals:**
- 不实现 TEST 草稿沙箱（已有 `task-test-run` 规范覆盖，本期不动）。
- 不做工作流编排的条件分支/参数化运行（沿用拓扑天然表达）。
- 不做全局实例运维大盘（task-flow 退役后由驾驶舱兜底，单列运维视图留作后续）。
- 不引入 Agent/MCP 的 run 工具（本期仅 REST + 闸门出口；MCP 复用同一 application 写方法，留二期）。

## Decisions

### D1：内层子 Tab —— 视图内自管，不复用顶层 Workspace tab

数据开发壳内维护一份独立的子 Tab 列表（`{kind: 'canvas'|'editor', refId}`），用顶层同款 `TabStrip` 渲染。**为何不复用顶层 store**：左树必须跨子 Tab 常驻，若每个编辑器是顶层 tab，左树无处安放、且会与"驾驶舱/报表"等异构视图混在一条 tab 条上，破坏 IDE 形态。自管子 Tab 让壳成为一个自洽的 mini-IDE，去重键 `{kind,id}`。
- *备选*：每任务开顶层 tab —— 否决，左树无法常驻、tab 条语义混乱。
- *状态归属*：子 Tab 列表是壳的局部 state（视图卸载即弃），无需进 workspace 持久化快照；工作流/任务的真相仍在后端。

### D2：编辑子 Tab = TaskEditPanel 能力 + Monaco，搬出侧栏

把 `TaskEditPanel` 的配置/参数行/替换预览逻辑整体迁入编辑子 Tab 组件，脚本区 `<textarea>` 换 `CodeEditor`（Monaco），`language` 由 `type` 派生（SQL→`sql`，SHELL→`shell`）。**为何复用而非重写**：参数序列化、预览端点调用、保存/发布逻辑已验证可用，只换承载壳与编辑控件。侧栏 `task-edit` 注册随之移除。

### D3：手动触发 = 正式实例，走现有执行链（点名复用入口）

`POST /api/tasks/{id}/run` 和 `POST /api/workflows/{id}/run` 起的是**正式实例**，复用现有执行链，因此 `logs/stream` 与 `workflow-instances/events/stream` 原样可用——**零新增观测通道**。这是选它而非 TEST 沙箱的关键收益：TEST 需要单独的不进统计的临时通道，观测链路要重接一套。
- **工作流 /run = 薄包装 `WorkflowTriggerService.trigger(wf, "MANUAL", bizDate, null)` + 闸门**。`trigger_type` 列已存在，不另起平行触发服务。
- **任务 /run = 起 `run_mode=NORMAL` 正式 task_instance + 闸门**，经现有调度/执行链下发。"正式、计入统计"由 `run_mode=NORMAL` 判定，**不新增 `trigger_type` 列**。
- *约束*：任务须已发布、工作流须已上线方可正式触发。草稿验证路径（`task-test-run`）当前未实现，本变更不依赖它兜底。
- *cron*：手动触发不触碰 cron `next_fire`、不补历史。
- *不变量*：工作流触发严格遵守调度死锁防御四条（SKIP LOCKED 认领、乐观 CAS 推进、锁序 task→workflow、事务内只落库 HTTP 下发在事务外）——`WorkflowTriggerService` 已内建，无需重证。

### D4：写操作必经闸门

两个 `/run` 是写操作 → 构造 `ActionRequest` → `GatedActionService.submit` → `PolicyEngine` 裁决 + `agent_action` 留痕，无绕过。默认 L1 直执行；`policy_rules` 可按类型抬级（如 MANUAL+SHELL→L2 审批）。与 `task-test-run` 的闸门接法同构，便于规则统一。

### D5：DAG 运行态叠加在画布节点上

画布子 Tab 订阅所属工作流某实例的事件流，按 `node_key` 把运行态（运行中/成功/失败/等待）叠到节点样式上。**为何叠加而非跳详情视图**：用户要"在画布上看 DAG 等待/运行"，就地变色比切到 `workflow-instance-detail` 更贴 DataWorks。运行态着色用语义 token，与节点类型/选中态叠加不冲突。已有 `workflow-instance-detail` 视图保留作深看入口。

### D6：base 风格 Dialog 替代原生弹框

新增 `components/ui/dialog.tsx`（base UI 风格，非 `asChild` 用 `render` prop），替换 2 处 `window.prompt`（建文件夹、建工作流），并承载类目树 CRUD（重命名/删除确认）与新建任务表单。删除确认等"确无页内位置"的场景才用 Dialog，其余优先页内（编辑直接进子 Tab）。

### D7：树搜索为本地过滤

树已全量拉取 tasks/workflows（`size=500`），搜索做本地子串匹配即可，无需新增后端搜索端点。命中叶子的祖先文件夹强制展开以可定位；清空恢复原展开态（原展开态由 `useCatalogTreeStore` 持有）。

## Risks / Trade-offs

- **[正式实例语义被误用为试跑]** 用户可能把"运行"当草稿验证用，但正式触发要求已发布/已上线且进统计；且 `task-test-run` 未实现，"走测试运行"是空路 → 缓解：未发布/未上线时**禁用"运行"按钮**并显示"需先发布"且能跳发布（见 OQ1 结论），不提供指向未实现功能的引导。
- **[task_instance 加列无消费方]** 任务侧"正式/不计统计"已由 `run_mode=NORMAL` 判定，给 task_instance 加 `trigger_type` 当前**无任何消费方** → 决策：本期**不加该列**；若未来要"仅手动运行"筛选维度，届时再带明确消费方引入。
- **[左树常驻 + 子 Tab 卸载导致状态丢失]** 切走编辑子 Tab 再回来若卸载会丢未存编辑 → 缓解：子 Tab 内容用 `keep-alive`（隐藏而非卸载）或把编辑态提到壳级 state；优先隐藏式保活。
- **[Monaco 体积/SSR]** Monaco 仅浏览器可用 → 沿用既有 `dynamic(ssr:false)` 包装，不在编辑子 Tab 直接 import。
- **[移除 task-flow 丢失全局实例视图]** → 缓解：驾驶舱已有健康概览兜底；如缺，单列运维视图作后续增量，不阻塞本变更。
- **[浏览器验证缝]** 改了视图/布局/Monaco 挂载 → 必过 Browser Verification Gate：CopilotChat 仍渲染、子 Tab 切换无白屏、跑后日志流可见、节点变色生效，截图入 `tmp/` 后清理。

## Migration Plan

1. 后端先行：实例表 `trigger_type` 列就位 → application 层手动触发服务（接闸门）→ `POST /run` 两端点 + 测试（JUnit + WebTestClient）。`./mvnw install -DskipTests` 后单模块 compile 校验。
2. 前端 Dialog 组件 + 类目树增强（搜索/CRUD/去提示/缩进），独立可验证。
3. 壳改造：`workflow-canvas-view` → 数据开发 IDE（内层 TabStrip + 左树常驻 + 画布子 Tab + 编辑子 Tab），并入 `TaskEditPanel`/`CodeEditor`。
4. 注册表收口：移除 `sql-workbench`/`task-flow`/侧栏 `task-edit`，改标题，删废文件。
5. Browser Verification Gate 全链路实跑 → typecheck。
- *回滚*：前端改动按 commit 粒度可逆；后端 `/run` 端点为纯增量，移除不影响存量调度；`trigger_type` 列加默认值不破坏存量。

## Resolved Decisions（原 Open Questions，评审定稿）

- **D8 未发布/未上线时"运行"按钮 → 禁用 + 引导发布**（不做"一键发布并运行"）。理由：① `task-test-run` 未实现，"引导走测试运行"是空路，现实就是"要么先发布、要么别跑"；② 发布是有副作用的状态变更（出版本快照、转上线、可能被 cron 拾起），把它塞进"运行"按钮会把两个意图混成一个动作，易误把未写完的脚本点成"发布上线"。按钮在未发布/未上线时禁用并显示"需先发布"，提供跳转发布入口。"发布并运行"低摩擦按钮留作后续，且其确认框必须明示"你正在发布上线"。
- **D9 画布历史实例切换器 → 砍，本期只盯最近一次。** 切换器是多实例运维特性，归入已列为 non-goal 的"单列运维视图"；历史深看由现成的 `workflow-instance-detail` 兜底。现在做会把编辑态画布与历史浏览耦合，扩 scope。

## Open Questions

- 无（关键决策已定稿）。
