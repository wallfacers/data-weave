# Tasks

## 1. Schema 演进（向后兼容）

- [x] 1.1 `schema.sql`：`workflow_node` 加列 `node_type VARCHAR(32) DEFAULT 'TASK'`；`task_id` 去掉 `NOT NULL`
- [x] 1.2 `db/migration` 增量脚本（PG）：`workflow-canvas-pg.sql`（ADD COLUMN node_type + ALTER COLUMN task_id DROP NOT NULL，旧数据默认 TASK）
- [x] 1.3 `data.sql`：~~可选补 VIRTUAL 节点~~ 决策不动种子（注入既有工作流会与 total_tasks/实例种子失配破坏 demo）；node_type 默认 'TASK' 已覆盖新列，空 task_id 路径由 6.3 单测 + 10.5 E2E 验证
- [x] 1.4 H2 DDL 验证并入后端测试阶段（task 6.5）执行，避免中途反复启停 8080

## 2. 后端领域与实体

- [x] 2.1 `WorkflowNode` 实体加 `nodeType` 字段（getter/setter）
- [x] 2.2 `WorkflowNodeRepository`/`WorkflowEdgeRepository` 补 `findByWorkflowIdAndDeleted`
- [x] 2.3 `./mvnw -q -pl dataweave-master compile` 零错误

## 3. 后端 WorkflowService（application）

- [x] 3.1 工作流 CRUD：创建草稿、分页搜索、详情、编辑、软删、下线（REST 写直通 service，闸门在 MCP 路径，与既有 TaskService 一致）
- [x] 3.2 读 DAG：返回 nodes（含 node_key/node_type/task_id/name/pos_x/pos_y）+ edges（端点 node_key）+ 乐观锁 version
- [x] 3.3 整图保存：`@Transactional` 内按 node_key / (from,to) node_key 对账 upsert + 软删差集；置 `has_draft_change=1`
- [x] 3.4 保存校验：TASK 节点 task_id 非空、VIRTUAL 允许空；version 不一致抛 IllegalStateException（→409）
- [x] 3.5 发布：`validateWorkflowDagAcyclic`（已改只看未删边）→ 冻结 `dag_snapshot_json`（Jackson3，含各 TASK 节点 current_version_no）→ `current_version_no++`、`has_draft_change=0`、`status=ONLINE`（master 加 spring-boot-starter-json）
- [x] 3.6 `./mvnw -q -pl dataweave-master compile` 零错误

## 4. 后端 WorkflowController（interfaces）

- [x] 4.1 `WorkflowController`（`/api/workflows`）：CRUD 端点（POST/GET 分页/GET 详情/PUT 编辑/DELETE/offline）
- [x] 4.2 DAG 端点：GET/PUT `/api/workflows/{id}/dag`（整图保存，409 经 GlobalExceptionHandler 由 IllegalStateException 映射）
- [x] 4.3 发布端点：POST `/api/workflows/{id}/publish`（环路 IllegalStateException → 409 + 环路提示）
- [x] 4.4 DTO 复用 service records + `ApiResponse` 包装；CORS 既有配置已放行前端 origin
- [x] 4.5 `./mvnw -q -pl dataweave-api compile` 零错误

## 5. 虚拟节点零负载执行

- [x] 5.1 `WorkflowTriggerService.trigger()`：按 `node_type` 分流——VIRTUAL 节点直接建 `state=SUCCESS` 的 task_instance（started_at=finished_at=now、无 task_id、不下发）；并改用 findByWorkflowIdAndDeleted 排除软删节点
- [x] 5.2 下游 readiness（`pred.state<>'SUCCESS'`）对虚拟 SUCCESS 节点放行；`WorkflowStateService.aggregate` 将虚拟 SUCCESS 计入；completed_tasks 初值=虚拟节点数
- [x] 5.3 确认 `SchedulerKernel` 只认领 WAITING——虚拟节点为 SUCCESS 从不进认领/下发路径

## 6. 后端测试

- [x] 6.1 `WorkflowServiceTest`：CRUD、整图保存对账（新增/更新/软删）、TASK 空 task_id 拒绝、乐观锁冲突
- [x] 6.2 发布单测：无环成功（status=ONLINE + 版本号自增）、有环拒绝（版本号不变）
- [x] 6.3 虚拟节点单测：物化即 SUCCESS（taskId null + finishedAt）、下游绑定物化（活体内核推进证明解锁）
- [x] 6.4 `WorkflowControllerTest` WebTestClient 端到端：create → saveDag → readDag → publish + 分页
- [x] 6.5 测试通过（8/8）。**修复**：task_instance.task_id 同样放宽可空（虚拟节点实例不绑任务）——schema.sql + 迁移脚本已补

## 7. 前端依赖与类型

- [x] 7.1 `pnpm add @xyflow/react`（装到 12.11.0，支持 React 19.2.4）；import `@xyflow/react/dist/style.css`
- [x] 7.2 `lib/types.ts`：加 WorkflowDef / DagNode / DagEdge / DagView / DagPayload / WorkflowPage 类型
- [x] 7.3 `pnpm typecheck` 零错误

## 8. 前端画布视图

- [x] 8.1 `components/workspace/views/workflow-canvas-view.tsx`：ReactFlowProvider + 容器（"use client"），加载工作流列表/任务/DAG
- [x] 8.2 自定义节点组件：TASK（实心卡片+DatabaseIcon）/ VIRTUAL（虚线圆角+CircleIcon），shadcn token 区分
- [x] 8.3 左侧任务面板：task_def 列表 + HTML5 DnD（application/dw-task）拖入画布建 TASK 节点（screenToFlowPosition 落点）
- [x] 8.4 工具栏：DropdownSelect 选工作流 + 新建 + 虚拟节点 + 保存草稿 + 发布；脏标记 +「未保存改动/有未发布改动」徽标
- [x] 8.5 连线建边 + 本地即时环路检测（wouldCreateCycle DFS，成环拒绝 + toast）
- [x] 8.6 保存草稿（整图 PUT，回传 version）、发布（dirty 拦截）；错误处理（409 冲突提示、发布失败提示，编辑态不丢）
- [x] 8.7 注册视图：`views.ts`（ViewType + VIEW_META「工作流编排」）+ `registry.tsx`（WorkflowSquare01Icon）
- [x] 8.8 `pnpm typecheck` 零错误

## 9. MCP 工具（可选，收尾，可裁剪）

- [ ] 9.1 ~~`McpToolRegistry` 新增 workflow 工具~~ 按 design Open Question 决策：v1 先交付 REST + 画布跑通，Agent 编排工具留后续迭代（不阻塞画布可用）
- [ ] 9.2 同上，延后

## 10. 端到端验证（Browser Verification Gate）

- [x] 10.1 后端 8090(H2) + 前端 3001 启动（避开用户占用的 8080/8081/3000），admin 登录后 `?open=workflow-canvas` 打开画布视图，**完整渲染**
- [x] 10.2 画布渲染验证：工具栏（工作流选择器/新建/虚拟节点/保存草稿/发布 + 已上线/未发布改动徽标）、左侧 3 个可拖拽任务、ReactFlow 节点+边+Controls+MiniMap、左栏 CopilotChat 输入框正常
- [x] 10.3 整图保存→重新读图持久性：curl 活体验证 3 节点 2 边正确回显（坐标/类型/边一致）
- [x] 10.4 有环图发布 → 后端 409 拒绝并带环路节点路径「101 → 102 → 101」；前端 wouldCreateCycle 本地拒绝已实现（代码+typecheck）
- [x] 10.5 虚拟节点零负载：单测 + curl 验证物化即 SUCCESS、下游解锁（活体内核推进）
- [x] 10.6 当前页 console **0 error 0 warning**（稳态）；浏览器渲染因离线 next/font/google 需临时换系统字体，验证后已还原 layout.tsx（git diff 无残留）；tmp 产物已清理
- [x] 10.7 全链路活体 REST（8090）：创建→整图保存(虚拟+fan-out)→读图→发布(ONLINE v1)→有环拒绝(409)→乐观锁冲突(409) 全通过
