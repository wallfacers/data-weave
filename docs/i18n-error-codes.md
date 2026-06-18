# DataWeave i18n 错误码 / 文案对照表

> 本表由 `messages.properties`（zh-CN 基底）+ `messages_en_US.properties` 自动整理，是后端 i18n message code 的参考真相源。
> 英文为随变更产出的机器译稿，**待母语校对**；数据中台术语（cron / DAG / SLA / lineage / OOM 等）保留英文原词。
> code 命名规范 `<domain>.<semantic>`，**稳定永不复用**；占位符用 MessageFormat `{0}`/`{1}`。

## 一、错误码 / 异常（按 UI locale 本地化，经 `BizException` + `GlobalExceptionHandler`）

| Code | zh-CN | en-US |
|------|-------|-------|
| `approval.expired` | 审批单 {0} 已超时过期，无法批准。 | Approval {0} has expired and cannot be approved. |
| `approval.l3_confirm_required` | L3 不可逆操作需回输目标对象名「{0}」二次确认。 | L3 irreversible action requires retyping the target object name \"{0}\" to confirm. |
| `approval.not_found` | 未找到审批单 {0} | Approval {0} not found |
| `approval.rejected` | 审批单 {0} 已拒绝。 | Approval {0} has been rejected. |
| `approval.wrong_state` | 审批单 {0} 状态为 {1}，不可批准。 | Approval {0} is in state {1} and cannot be approved. |
| `approval.wrong_state_on_reject` | 审批单 {0} 状态为 {1}，不可拒绝。 | Approval {0} is in state {1} and cannot be rejected. |
| `archive.file.invalid_key` | 非法归档键：{0} | Invalid archive key: {0} |
| `archive.file.read_failed` | 文件归档读取失败：{0} | File archive read failed: {0} |
| `archive.file.write_failed` | 文件归档写入失败：{0} | File archive write failed: {0} |
| `archive.s3.check_failed` | S3 归档检查失败：{0} | S3 archive check failed: {0} |
| `archive.s3.read_failed` | S3 归档读取失败：{0} | S3 archive read failed: {0} |
| `archive.s3.write_failed` | S3 归档写入失败：{0} | S3 archive write failed: {0} |
| `auth.account_disabled` | 账号已禁用 | Account disabled |
| `auth.credentials.required` | username 和 password 不能为空 | username and password are required |
| `auth.invalid_credentials` | 用户名或密码错误 | Invalid username or password |
| `auth.unauthenticated` | 未登录 | Not authenticated |
| `auth.user_not_found` | 用户 {0} 不存在 | User {0} not found |
| `catalog.cycle` | 不能把文件夹移动到自身或其后代之下 | Cannot move a folder under itself or one of its descendants |
| `catalog.invalid` | 类目入参非法（如跨项目、空名、非法 entityType） | Invalid catalog argument (e.g. cross-project, empty name, or invalid entityType) |
| `catalog.node.not_empty` | 文件夹非空，请先清空子文件夹或移走已归类资产 | Folder is not empty; clear its subfolders or reassign cataloged assets first |
| `catalog.not_found` | 文件夹或资产不存在：{0} | Folder or asset not found: {0} |
| `catalog.path.derived` | path 为后端派生字段，禁止传入 | path is a backend-derived field and must not be supplied |
| `catalog.tag.duplicate` | 标签名已存在：{0} | Tag name already exists: {0} |
| `cli.auth.invalid` | CLI 鉴权失败（缺少或错误的 X-DW-Token） | CLI authentication failed (missing or invalid X-DW-Token) |
| `cluster.auth_failed` | 鉴权失败 | Authentication failed |
| `cluster.event.unknown` | 未知事件类型：{0}，期望 started/finished/failed | Unknown event type: {0}, expected started/finished/failed |
| `cluster.task_instance_id.invalid` | 无效的 taskInstanceId：{0} | Invalid taskInstanceId: {0} |
| `cluster.task_instance_id.required` | taskInstanceId 不能为空 | taskInstanceId is required |
| `common.internal_error` | 服务器内部错误 | Internal server error |
| `common.network_error` | 网络异常 | Network error |
| `common.request_failed` | 请求处理失败 | Request failed |
| `common.success` | 成功 | Success |
| `gate.pending` | 该操作为 {0}，已创建审批单 #{1}，待人工批准。 | This action is {0}; approval #{1} created, awaiting human approval. |
| `gate.rejected` | 操作被策略禁止（{0}）：{1} | Operation rejected by policy ({0}): {1} |
| `ops.recover.no_effect` | 恢复未生效（实例非失败态或不存在） | Recovery had no effect (instance not in a failed state or not found) |
| `ops.recover.triggered` | 已触发断点恢复 | Checkpoint recovery triggered |
| `ops.rerun.no_effect` | 重跑未生效（实例不存在或非终态） | Rerun had no effect (instance not found or not in a terminal state) |
| `ops.rerun.triggered` | 已触发整流重跑 | Full rerun triggered |
| `policy.reason.base_cmd_no_match` | 命令前缀未命中白名单规则，宁严按 L2 | Command prefix not on the allowlist; defaulting strictly to L2 |
| `policy.reason.base_cmd_prefix` | 命中命令前缀规则 {0} | Matched command prefix rule {0} |
| `policy.reason.base_level` | 基础等级 {0}（{1}） | Base level {0} ({1}) |
| `policy.reason.base_no_tool` | 无 command/toolName，宁严按 L2 | No command/toolName provided; defaulting strictly to L2 |
| `policy.reason.base_tool_hit` | 命中工具规则 {0} | Matched tool rule {0} |
| `policy.reason.base_tool_no_match` | 工具未命中规则，宁严按 L2 | Tool not matched by any rule; defaulting strictly to L2 |
| `policy.reason.batch_over` | 批量 {0} > 阈值 {1} → 抬升至 L2 | Batch {0} > threshold {1} -> escalated to L2 |
| `policy.reason.foreign` | 目标不归属本平台 → 抬升至 L2 | Target not owned by this platform -> escalated to L2 |
| `policy.reason.injection` | 命令含重定向/命令分隔/子命令注入 → 抬升至 {0} | Command contains redirect/separator/subshell injection -> escalated to {0} |
| `policy.reason.prod_env` | prod 环境的 L1 → 抬升至 L2 | prod environment L1 -> escalated to L2 |
| `project.member.not_found` | 成员不存在：{0} | Member not found: {0} |
| `project.member.required` | userId 和 roleId 不能为空 | userId and roleId are required |
| `project.not_found` | 项目不存在：{0} | Project not found: {0} |
| `role.not_found` | 角色不存在：{0} | Role not found: {0} |
| `role.permission_ids.required` | permissionIds 不能为空 | permissionIds is required |
| `schedule.bizdate.empty` | bizDate 为空，无法解析日期占位符 | bizDate is empty, cannot parse date placeholder |
| `schedule.bizdate.format` | 非法 bizDate（需 yyyy-MM-dd 或 yyyyMMdd）：{0} | Illegal bizDate (expected yyyy-MM-dd or yyyyMMdd): {0} |
| `schedule.bizdate.illegal` | 非法 bizDate：{0} | Illegal bizDate: {0} |
| `schedule.offset.dangling` | 非法偏移表达式（末尾悬空运算符）：{0} | Illegal offset expression (dangling trailing operator): {0} |
| `schedule.offset.illegal` | 非法偏移表达式：{0} | Illegal offset expression: {0} |
| `schedule.param.circular` | 循环引用参数：{0} | Circular parameter reference: {0} |
| `schedule.placeholder.empty` | 空占位符（占位符内部为空） | Empty placeholder (nothing inside the braces) |
| `schedule.placeholder.nested` | 非法嵌套占位符：不支持占位符内再嵌套，位置 {0} | Illegal nested placeholder: nesting inside a placeholder is not supported, position {0} |
| `schedule.placeholder.unclosed` | 未闭合的占位符（缺少右花括号），位置 {0} | Unclosed placeholder (missing closing brace), position {0} |
| `schedule.placeholder.undefined` | 未定义的占位符：{0} | Undefined placeholder: {0} |
| `tag.entity_type.empty` | entityType 不能为空 | entityType cannot be empty |
| `tag.entity_type.invalid` | 非法 entityType: {0} | Invalid entityType: {0} |
| `tag.name.duplicate` | 标签名已存在: {0} | Tag name already exists: {0} |
| `tag.name.empty` | 标签名不能为空 | Tag name cannot be empty |
| `tag.not_found` | 标签不存在: {0} | Tag not found: {0} |
| `task.not_found` | 任务不存在：{0} | Task not found: {0} |
| `task.not_published` | 任务未发布，需先发布再运行 | Task is not published; publish it before running |
| `task_instance.not_found` | 任务实例不存在：{0} | Task instance not found: {0} |
| `tenant.not_found` | 租户不存在：{0} | Tenant not found: {0} |
| `user.not_found` | 用户不存在：{0} | User not found: {0} |
| `user.role_ids.required` | roleIds 不能为空 | roleIds is required |
| `workflow.edge.node_not_found` | 边引用了不存在的节点：{0} → {1} | Edge references a non-existent node: {0} -> {1} |
| `workflow.graph.cycle` | 工作流发布失败：DAG 存在环路（节点 {0}），请打断环路后重试。 | Workflow publish failed: the DAG contains a cycle (nodes {0}). Break the cycle and retry. |
| `workflow.graph.dependency_cycle` | 依赖创建失败：将导致工作流依赖成环（{0}）。 | Dependency creation failed: this would create a cycle in the workflow dependency graph ({0}). |
| `workflow.graph.self_dependency` | 依赖创建失败：工作流不能依赖自身。 | Dependency creation failed: a workflow cannot depend on itself. |
| `workflow.node.key_duplicate` | 节点 {0} 重复 | Node {0} is duplicated |
| `workflow.node.key_missing` | 节点缺少 nodeKey | Node is missing nodeKey |
| `workflow.node.task_unbound` | 任务节点未绑定任务：{0} | Task node is not bound to a task: {0} |
| `workflow.not_found` | 工作流不存在：{0} | Workflow not found: {0} |
| `workflow.not_online` | 工作流当前未上线 | Workflow is not online |
| `workflow.publish.empty` | 工作流无节点，无法发布 | Workflow has no nodes and cannot be published |
| `workflow.publish.no_change` | 无未发布改动 | No unpublished changes |
| `workflow.stale_version` | 工作流已被他人修改，请刷新后重试 | Workflow has been modified by another user, please refresh and retry |
| `workspace.state.too_long` | workspace 状态超长（> {0} 字符） | workspace state too long (> {0} characters) |

## 二、Agent 动态文案（按 agent locale 本地化：意图回复 / 执行结果 / 诊断）

| Code | zh-CN | en-US |
|------|-------|-------|
| `agent.common.no_data` | (无数据) | (no data) |
| `agent.context.instance` | 实例=#{0} | instance=#{0} |
| `agent.context.module` | 模块={0} | module={0} |
| `agent.context.node` | 节点={0} | node={0} |
| `agent.context.pathname` | 路径={0} | path={0} |
| `agent.context.prefix` | [上下文] | [Context] |
| `agent.context.task` | 任务=#{0} | task=#{0} |
| `agent.diagnosis.no_suggestions` | （暂无建议） | (no suggestions) |
| `agent.diagnosis.none` | 当前没有失败的任务实例可诊断。 | No failed task instance available to diagnose right now. |
| `agent.diagnosis.root_cause_heading` | ## 根因 | ## Root cause |
| `agent.diagnosis.suggestions_heading` | ### 修复建议 | ### Suggested fixes |
| `agent.diagnosis.unknown` | (未知) | (unknown) |
| `agent.fleet.table_header` | \| 节点 \| 状态 \| CPU% \| 内存% \| 磁盘% \| load \| 运行任务 \| | \| Node \| Status \| CPU% \| Mem% \| Disk% \| load \| Running tasks \| |
| `agent.help.diagnosis` | - **诊断**：如「帮我诊断为什么失败」——分析最近失败实例并给出修复建议。 | - **Diagnose**: e.g. "diagnose why it failed" -- analyze the latest failed instance and suggest fixes. |
| `agent.help.fleet` | - **查机器**：如「看看集群机器状态」——列出 worker 节点与资源水位。 | - **Fleet**: e.g. "show cluster node status" -- list worker nodes and resource usage. |
| `agent.help.intro` | 我是 DataWeave Agent（当前为 MVP 规则 mock 引擎）。我现在支持以下问法： | I am the DataWeave Agent (currently an MVP rule-based mock engine). I support the following queries: |
| `agent.help.lineage` | - **血缘问答**：如「GMV 受哪些表影响」——返回「指标 → SQL → 物理表」链路。 | - **Lineage**: e.g. "which tables does GMV depend on" -- return the "metric -> SQL -> physical table" chain. |
| `agent.help.metric` | - **指标查询**：如「GMV 是多少」——返回指标值与口径溯源。 | - **Metric query**: e.g. "what is GMV" -- return the metric value and its definition lineage. |
| `agent.help.retry` | 请换一种上述问法再试。 | Please try one of the queries above. |
| `agent.help.sql` | - **Text-to-SQL**：如「orders 表有多少条」「查一下 orders」——生成只读 SQL 并返回表格。 | - **Text-to-SQL**: e.g. "how many rows in orders" / "show orders" -- generate read-only SQL and return a table. |
| `agent.help.task` | - **建任务**：如「创建一个任务，每天 8 点执行 `select count(*) from orders`」——建任务并上线。 | - **Create task**: e.g. "create a task that runs `select count(*) from orders` daily at 8" -- create and bring it online. |
| `agent.lineage.affected_tables` | 受影响的物理表： | Affected physical tables: |
| `agent.lineage.intro` | 指标 **{0}** 的影响链路（指标 → SQL → 物理表）： | Impact chain of metric **{0}** (metric -> SQL -> physical table): |
| `agent.lineage.no_record` | （无记录） | (no records) |
| `agent.metric.source_heading` | 口径溯源： | Definition lineage: |
| `agent.metric.source_sql` | - 口径 SQL：`{0}` | - Definition SQL: `{0}` |
| `agent.metric.source_table` | - 来源表：`{0}` | - Source table: `{0}` |
| `agent.metric.value` | 指标 **{0}** 的当前值为 **{1}**。 | The current value of metric **{0}** is **{1}**. |
| `agent.metric.version` | - 版本：v{0} | - Version: v{0} |
| `agent.sql.converted` | 已将问题转换为 SQL： | Converted your question into SQL: |
| `agent.sql.empty_result` | (空结果) | (empty result) |
| `agent.sql.rejected` | 已生成 SQL：`{0}`，但未通过只读校验：{1} | Generated SQL: `{0}`, but it failed the read-only check: {1} |
| `agent.sql.result_heading` | 执行结果： | Result: |
| `agent.task.created` | 已创建并上线任务： | Task created and brought online: |
| `agent.task.field_content` | - 执行内容：`{0}` | - Content: `{0}` |
| `agent.task.field_cron` | - 调度（cron）：`{0}`（每天 {1}:00） | - Schedule (cron): `{0}` (daily at {1}:00) |
| `agent.task.field_name` | - 任务名：**{0}** | - Name: **{0}** |
| `agent.task.field_status` | - 状态：**{0}** | - Status: **{0}** |
| `agent.task.field_type` | - 类型：`{0}` | - Type: `{0}` |
| `agent.task.mock_advanced` | 已 mock 推进一条运行实例至 SUCCESS。 | A mock run instance has been advanced to SUCCESS. |
| `agent.task.name_gmv` | GMV 统计任务 | GMV metric task |
| `agent.task.name_nl` | 自然语言任务-{0} | NL task-{0} |
| `diagnosis.common.task_fallback` | 任务#{0} | Task #{0} |
| `diagnosis.contention.root_cause` | {0} 系统 load {1}、并发 {2} 个任务，CPU {3}%，资源争抢导致任务超时/失败。 | {0} system load {1}, {2} concurrent tasks, CPU {3}%; resource contention caused the task to timeout / fail. |
| `diagnosis.contention.title` | {0} 失败 · 节点资源争抢 | {0} failed · node resource contention |
| `diagnosis.fix.cap_node_weight` | 为 {0} 设置调度权重上限 | Cap scheduling weight for {0} |
| `diagnosis.fix.migrate_node` | 迁移到空闲节点重跑 | Migrate to an idle node and rerun |
| `diagnosis.fix.not_found` | 未找到诊断记录 #{0} | Diagnosis #{0} not found |
| `diagnosis.fix.rerun_in_place` | 原地重跑 | Rerun in place |
| `diagnosis.fix.rerun_more_memory` | 调大 executor 内存重跑 | Rerun with more executor memory |
| `diagnosis.fix.rerun_offset` | 错峰后原地重跑 | Rerun in place at an off-peak time |
| `diagnosis.fix.summary_label` | 一键修复 {0} · 诊断 #{1} | One-click fix {0} · diagnosis #{1} |
| `diagnosis.oom.root_cause` | {0} 内存使用率 {1}%，任务触发 OutOfMemoryError 被容器终止。 | {0} memory usage {1}%; task terminated by container with OutOfMemoryError. |
| `diagnosis.oom.root_cause_with_contention` | {0} 内存使用率 {1}%，任务触发 OutOfMemoryError 被容器终止；同时段该节点并发运行 {2} 个任务，存在资源争抢。 | {0} memory usage {1}%; task terminated by container with OutOfMemoryError; {2} concurrent tasks on this node at the time caused resource contention. |
| `diagnosis.oom.title` | {0} 失败 · 节点内存不足导致 OOM | {0} failed · node OOM |
| `diagnosis.unknown.root_cause` | 未命中已知资源类根因。{0} 资源水位正常（内存 {1}%、CPU {2}%）。建议查看任务日志与上游依赖。 | No known resource-class root cause matched. {0} resource levels nominal (memory {1}%, CPU {2}%). Check task logs and upstream dependencies. |
| `diagnosis.unknown.title` | {0} 失败 · 待进一步排查 | {0} failed · needs further investigation |
| `executor.create_task.default_name` | 自然语言任务 | NL task |
| `executor.create_task.success` | 已创建并上线任务「{0}」（cron {1}），并 mock 推进一条成功实例。 | Created and onlined task \"{0}\" (cron {1}); a mock success instance has been advanced. |
| `executor.fix_cap_weight.success` | 已为节点 {0} 设置调度权重上限，后续将减少该节点的任务并发（mock 生效）。 | Capped scheduling weight for node {0}; this node will host fewer concurrent tasks going forward (mock effect). |
| `executor.fix_migrate.success` | 已将任务迁移到空闲节点 {0} 重跑，运行成功。 | Migrated task to idle node {0} and rerun; now running. |
| `executor.fix_more_memory.success` | 已调大 executor 内存并在 {0} 重跑，运行成功。 | Bumped executor memory and rerun on {0}; now running. |
| `executor.fix_rerun.success` | 已原地重跑，运行成功。 | Rerun in place succeeded; now running. |
| `executor.instance_op.bad_id` | 实例 id 非法：{0} | Invalid instance id: {0} |
| `executor.instance_op.failed` | {0} 未生效：{1} | {0} had no effect: {1} |
| `executor.instance_op.killed` | 已终止工作流实例（→ {0}）。 | Workflow instance killed (→ {0}). |
| `executor.instance_op.paused` | 已暂停工作流实例（→ {0}）。 | Workflow instance paused (→ {0}). |
| `executor.instance_op.resumed` | 已恢复工作流实例（→ {0}）。 | Workflow instance resumed (→ {0}). |
| `executor.node_exec.gateway_absent` | node_exec 网关未接线（worker-exec 未部署） | node_exec gateway not wired (worker-exec not deployed) |
| `executor.rerun_workflow.no_effect` | 整流重跑未生效（实例不存在）。 | Full rerun had no effect (instance not found). |
| `executor.rerun_workflow.success` | 已整流重跑工作流实例（全节点重置）。 | Full rerun of workflow instance (all nodes reset). |
| `executor.resume_workflow.no_effect` | 断点恢复未生效（实例非失败态或不存在）。 | Resume had no effect (instance not in a failed state or not found). |
| `executor.resume_workflow.success` | 已断点恢复工作流实例（保留成功节点，从失败点续跑）。 | Resumed workflow instance from failure point (kept successful nodes). |
| `executor.task_rerun.success` | 已重跑任务实例 #{0}，新实例 #{1} 运行成功。 | Rerun task instance #{0}; new instance #{1} running successfully. |
| `executor.task_run.success` | 已手动触发任务 #{0} 的正式运行。 | Manually triggered production run for task #{0}. |
| `executor.test_run.missing_task_id` | 缺少任务 id | Missing task id |
| `executor.test_run.success` | 已提交任务 #{0} 的测试运行（草稿内容，留痕）。 | Submitted test run for task #{0} (draft content; audit-trail only). |
| `executor.trigger_workflow.success` | 已手动触发工作流「{0}」。 | Manually triggered workflow \"{0}\". |
| `executor.unsupported_action` | 不支持的动作类型：{0} | Unsupported action type: {0} |

## 三、fallback 验证专用键

| Code | 说明 |
|------|------|
| `test.fallback_only_zh` | 故意仅存于 zh 基底，用于 `GlobalExceptionHandlerI18nTest` 验证 en 缺失 key 回退 zh 行为 |

