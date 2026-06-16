## 1. 后端：手动触发正式实例（manual-run-trigger）

- [x] 1.1 工作流 /run：`WorkflowController` 加 `POST /api/workflows/{id}/run` = 闸门（`ActionRequest`→`GatedActionService.submit`）+ 薄包装现成 `WorkflowTriggerService.trigger(wf, "MANUAL", bizDate, null)`（`workflow_instance.trigger_type` 列已存在，零 DDL；死锁四不变量服务已内建），返回 workflowInstanceId；未上线拒绝
- [x] 1.2 任务 /run：定位任务侧实例创建/下发入口（task_instance + `run_mode`），`TaskController` 加 `POST /api/tasks/{id}/run` = 闸门 + 起 `run_mode=NORMAL` 正式 task_instance → 下发，返回 instanceId；不触碰 cron next_fire；未发布拒绝。**不新增 `trigger_type` 列**
- [x] 1.3 `policy_rules` 增 MANUAL 运行默认分级（L1 直执行），验证可数据驱动抬级（如 SHELL 运行→L2 审批）
- [x] 1.4 测试：JUnit + WebTestClient 覆盖——工作流手动起正式实例（trigger_type=MANUAL）、任务手动起 run_mode=NORMAL 进统计、不动 cron、未发布/未上线拒绝、L1 直执行、规则收紧后 PENDING_APPROVAL、`agent_action` 留痕
- [x] 1.5 `./mvnw install -DskipTests` 后 `-pl <改动模块> compile` 校验零错误

## 2. 前端：base 风格 Dialog 组件

- [x] 2.1 新增 `components/ui/dialog.tsx`（base UI 风格，`render` prop 非 asChild），支持标题 + 表单 body + 确认/取消
- [x] 2.2 替换 `catalog-tree.tsx` 建文件夹的 `window.prompt` → Dialog
- [x] 2.3 替换 `workflow-canvas-view.tsx` 建工作流的 `window.prompt` → Dialog
- [x] 2.4 `pnpm typecheck` 零错误

## 3. 前端：类目树增强（task-workflow-catalog）

- [x] 3.1 顶部加搜索输入框，本地子串过滤 task/workflow 叶子（大小写不敏感）；命中叶子祖先文件夹自动展开；清空恢复原展开态
- [x] 3.2 叶子行内操作（hover/右键）：重命名（PUT /api/{tasks|workflows}/{id}）+ 删除（DELETE 软删 + Dialog 二次确认），成功后刷新树
- [x] 3.3 移除常驻拖拽提示文案（`catalog-tree.tsx:369-374`）
- [x] 3.4 修正缩进：叶子图标与同级子文件夹左对齐、相对父级缩进恰好一级，核对各层级无错位
- [x] 3.5 `pnpm typecheck` 零错误

## 4. 前端：编辑子 Tab（Monaco + 配置）

- [x] 4.1 新建编辑子 Tab 组件：迁入 `TaskEditPanel` 的配置/参数行/替换预览/保存/发布逻辑
- [x] 4.2 脚本区 `<textarea>` 换 `CodeEditor`（Monaco，`dynamic ssr:false`），`language` 由 `type` 派生（SQL→sql / SHELL→shell）
- [x] 4.3 编辑子 Tab 加"运行"按钮：调 `POST /api/tasks/{id}/run` 拿 instanceId，内嵌区域接 `/api/ops/instances/{id}/logs/stream` 流式日志。**任务未发布时按钮禁用 + 显示"需先发布" + 提供跳转发布入口**（D8，不做一键发布并运行）
- [x] 4.4 `pnpm typecheck` 零错误

## 5. 前端：数据开发 IDE 壳（data-development-ide + workflow-canvas）

- [x] 5.1 `workflow-canvas-view.tsx` 升格为 IDE 壳：左树常驻 + 右侧内层 `TabStrip`（自管子 Tab 列表，去重键 {kind,id}）
- [x] 5.2 画布逻辑收为"画布子 Tab"：一个工作流一个；点树工作流叶子开/激活；保留拖建节点/连线/保存/发布。先 grep 确认 `workflow-canvas-view` 对 `task-flow-view` 无 live import（避免空断言）
- [x] 5.3 点树任务叶子 → 开/激活编辑子 Tab；IDE 内"新建任务"经 Dialog 收集名+类型，建后直接开编辑子 Tab 进编辑态
- [x] 5.4 子 Tab 切换用隐藏式保活（keep-alive），避免编辑态丢失
- [x] 5.5 画布子 Tab 订阅 `/api/ops/workflow-instances/{id}/events/stream`，按 node_key 把运行态叠加变色（语义 token，不掩盖类型/选中态，支持 Last-Event-ID 续传）
- [x] 5.6 画布子 Tab 加"运行"按钮：调 `POST /api/workflows/{id}/run` 拿 workflowInstanceId 并订阅其事件流。**工作流未上线时按钮禁用 + 提示需先发布**（D8）。本期只盯最近一次运行着色，不做历史实例切换器（D9）
- [x] 5.7 `pnpm typecheck` 零错误

## 6. 前端：注册表收口与废件清理（agent-workspace）

- [x] 6.1 `views.ts`/`registry.tsx`：`workflow-canvas` 标题改"数据开发"；移除 `sql-workbench`、`task-flow`
- [x] 6.2 `task-flow` 退出 Pinned 底座（去 `task-flow` 的 `defaultPinned`；PINNED 由**五减为四**：驾驶舱/数据新鲜度/业务报表/系统指标）。同步修 `store.test.ts` 注释/断言（原"四个"标题已 stale，实为 5→4）
- [x] 6.3 删除废件：`components/sql-workbench.tsx`、`views/sql-workbench-view.tsx`、`views/task-flow-view.tsx`、侧栏 `task-edit` 注册与 `components/ops/task-edit-panel.tsx`（能力已迁入编辑子 Tab）
- [x] 6.4 启动菜单（Launcher）自动随注册表更新——核对"数据开发"在列、"任务开发/任务流"已无
- [x] 6.5 `pnpm typecheck` 零错误

## 7. 验证与收尾

- [x] 7.1 Browser Verification Gate 全链路实跑：打开数据开发 IDE → 左树搜索/CRUD → 点任务开编辑子 Tab（Monaco 高亮）→ 跑任务看日志 → 点工作流开画布 → 跑工作流看节点变色；CopilotChat 仍渲染、console 无 error
- [x] 7.2 截图/trace 写入 `tmp/`，验证后清理，不留仓库
- [x] 7.3 确认无残留对已删视图/组件的 import；后端 `compile` + 前端 `typecheck` 双绿
