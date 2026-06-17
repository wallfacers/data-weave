## 1. Schema & Entity 基础（先行）

- [x] 1.1 更新 `schema.sql`
- [x] 1.2 更新所有对应 Java Entity 类
- [x] 1.3 修复 `WorkflowDependency.dateOffset` 类型
- [x] 1.4 修复 `AlertRule.createdBy`/`updatedBy` 类型
- [x] 1.5 修复 `NotificationChannel.createdBy`/`updatedBy` 类型
- [x] 1.6 新建 `WorkflowDefVersion` Entity 类 + `WorkflowDefVersionRepository`
- [x] 1.7 后端编译验证

## 2. 日期格式统一

- [x] 2.1 新建 `JacksonConfig.java`
- [x] 2.2 前端 `lib/types.ts`：`formatDateTime()` 改为输出 `yyyy-MM-dd HH:mm:ss` 格式

## 3. 任务 CRUD 后端

- [x] 3.1 重构 `TaskService`：拆出 CRUD 方法
- [x] 3.2 `TaskDefRepository` 新增搜索方法
- [x] 3.3 新建 `TaskController.java`
- [x] 3.4 后端编译验证

## 4. Cron 调度引擎

- [x] 4.1 新建 `CronScheduler.java`
- [x] 4.2 实现 cron 触发逻辑
- [x] 4.3 实现触发执行
- [x] 4.4 添加调度窗口判断
- [x] 4.5 添加 SLF4J 日志
- [x] 4.6 `WorkflowDefRepository` 新增 `findSchedulable()` 方法
- [x] 4.7 后端编译验证

## 5. 实例生命周期管理

- [x] 5.1 `OpsService` 新增方法
- [x] 5.2 实现状态机校验
- [x] 5.3 暂停逻辑
- [x] 5.4 恢复逻辑
- [x] 5.5 终止逻辑
- [x] 5.6 `OpsController` 新增端点
- [x] 5.7 后端编译验证

## 6. 日志系统改造

- [x] 6.1 `OpsService` 新增 `getLog(instanceId, offset, limit)` 方法
- [x] 6.2 `OpsController` 新增 `GET /instances/{id}/log` 端点
- [x] 6.3 后端编译验证

## 7. MCP 工具补齐

- [x] 7.1 `McpToolRegistry` 新增 `update_task` 工具
- [x] 7.2 `McpToolRegistry` 新增 `delete_task` 工具
- [x] 7.3 `McpToolRegistry` 新增 `pause_instance`/`resume_instance`/`kill_instance` 工具
- [x] 7.4 后端编译验证

## 8. 前端 — 任务管理列表

- [x] 8.1 新建 `task-search-bar.tsx`
- [x] 8.2 重写 `task-def-list.tsx`
- [x] 8.3 新建 `task-edit-drawer.tsx`
- [x] 8.4 整合 `task-flow-view.tsx`

## 9. 前端 — 运维面板增强

- [x] 9.1 修改 `instance-table.tsx`
- [x] 9.2 新建 `log-viewer.tsx`
- [x] 9.3 前端类型检查

## 10. 端到端验证

- [x] 10.1 后端全量编译
- [ ] 10.2 启动后端 + 前端，浏览器打开 `http://localhost:4000`
- [ ] 10.3 验证任务 CRUD：创建草稿 → 搜索 → 编辑 → 发布上线 → 下线 → 删除
- [ ] 10.4 验证实例操作：手动触发工作流 → 暂停 → 恢复 → 终止
- [ ] 10.5 验证日志查看：执行任务后查看完整日志（不截断）
- [ ] 10.6 验证日期格式：所有时间字段显示为 `yyyy-MM-dd HH:mm:ss`
- [ ] 10.7 验证 Agent 集成：通过对话让 Agent 创建/修改/删除任务，确认 MCP 工具可用
