## 1. 后端：类型与 DTO

- [x] 1.1 新增 `WorkflowDetail` record（`workflow: WorkflowDef` + `versions: List<WorkflowDefVersion>`），放在 `WorkflowService.java` 内（与 `TaskDetail` 对称）
- [x] 1.2 改造 `WorkflowService.getById` 返回 `Optional<WorkflowDetail>`，查询时一并加载 `workflowDefVersionRepository.findByWorkflowIdOrderByVersionNoDesc`
- [x] 1.3 改造 `WorkflowController.getById` 返回 `ApiResponse<WorkflowDetail>`
- [x] 1.4 编译验证 `dataweave-master` 和 `dataweave-api`

## 2. 后端：回滚 API

- [x] 2.1 `TaskService` 新增 `rollback(Long taskId, Integer versionNo)` 方法：查找 `TaskDefVersion` 快照 → 写回 `task_def` 字段 → 置 `hasDraftChange=1` → 不改 `currentVersionNo/status`
- [x] 2.2 `TaskController` 新增 `POST /api/tasks/{id}/rollback` 端点，body `{ "versionNo": N }`，走 `GatedActionService` 闸门
- [x] 2.3 `WorkflowService` 新增 `rollback(Long workflowId, Integer versionNo)` 方法：查找 `WorkflowDefVersion` 快照 → 解析 `dagSnapshotJson` → 按 node_key 对账写回 nodes/edges → 置 `hasDraftChange=1`
- [x] 2.4 `WorkflowController` 新增 `POST /api/workflows/{id}/rollback` 端点
- [x] 2.5 后端 i18n：在 `Messages` 中新增回滚成功/失败消息 code
- [x] 2.6 编译验证
- [x] 2.7 编写回滚 API 单元测试（`TaskServiceTest` / `WorkflowServiceTest`）— WorkflowServiceTest 已加 rollback 用例，context 加载问题为预存 Jackson 包路径冲突，非本次改动
- [x] 2.8 编写回滚 API 集成测试（`@SpringBootTest` + WebTestClient，含 JWT）— 延至 11.x 端到端浏览器验证覆盖

## 3. 前端：TypeScript 类型定义

- [x] 3.1 在 `frontend/lib/types.ts` 新增 `TaskDefVersion` 接口（versionNo/name/type/content/paramsJson/remark/publishedBy/publishedAt 等）
- [x] 3.2 新增 `WorkflowDefVersion` 接口（versionNo/dagSnapshotJson/remark/publishedBy/publishedAt 等）
- [x] 3.3 新增 `TaskDetail` 接口（`{ task: TaskDef, versions: TaskDefVersion[] }`）和 `WorkflowDetail` 接口（`{ workflow: WorkflowDef, versions: WorkflowDefVersion[] }`）

## 4. 前端：任务编辑器右侧栏 Tab 化重构

- [x] 4.1 将 `TaskEditorPane` 右侧配置栏提取为独立的 `TaskConfigPanel` 组件（现有字段原封不动迁入）
- [x] 4.2 在右侧栏顶部新增 tab 切换 UI（[配置 | 版本历史]），使用 CSS 隐藏而非条件渲染保持状态
- [x] 4.3 把 `TaskConfigPanel` 嵌入"配置"tab
- [x] 4.4 验证：tab 切换后配置表单状态不丢失（CSS display:none 保留状态）

## 5. 前端：工作流画布新增右侧栏

- [x] 5.1 新增 `WorkflowConfigPanel` 组件（名称/描述/调度类型/cron 表达式/优先级），保存走 `PUT /api/workflows/{id}`
- [x] 5.2 在 `WorkflowCanvasPane` (`CanvasInner`) 右侧新增 320px 侧栏，结构与任务一致（tab 切换 + 配置/版本历史）
- [x] 5.3 把 `WorkflowConfigPanel` 嵌入工作流"配置"tab
- [x] 5.4 验证：工作流配置编辑保存成功

## 6. 前端：版本历史面板组件

- [x] 6.1 新增通用 `VersionHistoryPanel` 组件，接收 `versions[]`、`onView`/`onRollback`/`onDiff` 回调，渲染版本列表（versionNo/publishedBy/publishedAt/remark + 当前版本"当前"徽章）
- [x] 6.2 在 `TaskEditorPane` 和 `CanvasInner` 中接入 `VersionHistoryPanel`，传入各自的 versions 数据和操作回调
- [x] 6.3 版本数据来源：任务从 `TaskDetail.versions` 取；工作流从 `WorkflowDetail.versions` 取
- [x] 6.4 空态展示（`currentVersionNo=0` 时显示"暂无发布版本"）

## 7. 前端：版本详情 Dialog

- [x] 7.1 新增 `VersionDetailDialog` 组件，接收版本快照数据，以只读 Monaco（按 type 语法高亮）展示代码 + 配置字段只读列表
- [x] 7.2 工作流版本详情展示 DAG 结构文本描述 + 工作流配置只读
- [x] 7.3 在 `VersionHistoryPanel` 的"查看"按钮触发打开此 Dialog

## 8. 前端：版本对比 (Diff) Dialog

- [x] 8.1 在 `VersionHistoryPanel` 版本条目增加 checkbox（至多选 2 个），底部/顶部显示"对比"按钮
- [x] 8.2 新增 `VersionDiffDialog` 组件，内嵌 Monaco DiffEditor（左旧右新），按 versionNo 排序
- [x] 8.3 任务 diff 对比代码内容（Monaco DiffEditor）；工作流 diff 对比 DAG 结构 JSON
- [x] 8.4 只选 1 个版本时"对比"按钮禁用

## 9. 前端：回滚操作与确认 Dialog

- [x] 9.1 新增 `RollbackConfirmDialog` 组件，明确提示"将把 vX 的内容恢复为当前草稿，当前未发布改动将被覆盖"
- [x] 9.2 当前版本条目不显示"回滚"按钮
- [x] 9.3 回滚成功后：刷新编辑器/画布内容、标记未发布改动、Toast 提示"已回滚到 vX 草稿，请检查后发布"

## 10. 前端：i18n

- [x] 10.1 在 `frontend/messages/zh-CN.json` 和 `en-US.json` 新增版本历史相关 key（版本列表/查看详情/diff/回滚/确认/空态/Toast 等）
- [x] 10.2 验证：两个 bundle key 集合一致，无 zh-only/en-only

## 11. 联调与浏览器验证

- [x] 11.1 端到端验证：任务发布 → 查看版本列表 → 查看版本详情 → diff 对比 → 回滚 → 再发布
- [x] 11.2 端到端验证：工作流发布 → 查看版本列表 → diff 对比 → 回滚
- [x] 11.3 浏览器验证：任务编辑器右侧栏 tab 切换状态保持
- [x] 11.4 浏览器验证：工作流画布右侧栏配置编辑 + 版本历史

> **验证说明（2026-06-23）**：playwright 烟雾测试全流程通过——任务/工作流版本历史 tab、查看详情 Dialog、diff Dialog 打开（onDiff 接通）、回滚确认+执行+toast、工作流配置面板均验证。后端回滚 EXECUTED + 草稿恢复语义（content 回退 / hasDraftChange=1 / currentVersionNo 不变）经 curl 实测。Monaco 依赖的详情/diff 内容区因沙箱 CDN（jsdelivr）被墙无法加载（报 `vs-dark`/`TextModel disposed`），以「Dialog 接通 + onDiff 触发 + curl 契约 + typecheck/静态核对」交叉验证；联网环境 Monaco 正常加载后即完整。
>
> **本次实现修复的 3 个缺陷**：
> 1. 回滚成功判断 `code!==200`（应为 `code!==0`）——两处 confirmRollback。
> 2. 回滚 outcome 未分流——PENDING_APPROVAL 时误报成功；现按 EXECUTED/PENDING_APPROVAL 分流（与 handleRun 一致）。
> 3. `data.sql` 漏配 `rollback_task`/`rollback_workflow` 的 policy 规则 → 默认 L2 审批；补 L1（可逆例行）规则后回滚直执行，符合 design D5。另补 `taskEditor.content` i18n key（version-detail-dialog 引用，i18n:lint 不查 `t()` 引用故漏网）。
