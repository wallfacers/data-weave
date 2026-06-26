# Weft i18n 错误码 / 文案对照表

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

## 二、fallback 验证专用键

| Code | 说明 |
|------|------|
| `test.fallback_only_zh` | 故意仅存于 zh 基底，用于 `GlobalExceptionHandlerI18nTest` 验证 en 缺失 key 回退 zh 行为 |

