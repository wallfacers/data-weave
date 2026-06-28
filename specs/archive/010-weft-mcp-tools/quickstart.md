# Quickstart: Weft 子特性 E —— MCP 工具重塑

**前置**:后端起(`:8000`,`/mcp` 开放);`mcp.auth.token` + `mcp.auth.tenant-id` + `mcp.auth.user-id` 已配(E1 身份)。本地 AI agent(Claude Code/Codex)配 MCP server 指向 `/mcp` + Bearer。

## 典型协作流(本地 AI 经 MCP 操作平台)

```jsonc
// 1. 列工具:无残留 AI 工具、无 create_task
→ tools/list
← [project_pull, project_push, project_diff, instance_logs,
   query_task_definitions, query_task_instances, query_fleet,
   query_metric, query_lineage, task_rerun, node_exec, approve_and_execute]

// 2. 读平台态(tenant-scoped,跨租户拒)
→ tools/call query_task_definitions {}
← [本租户任务定义…]

// 3. AI 改完本地文件后,经 MCP 推回(纯新增/更新 → L1 直通)
→ tools/call project_push { projectId:12, files:{…}, baseline:"a1b2…" }
← { outcome:"EXECUTED", created:{task:2}, snapshots:[…], newBaseline:"c3d4…" }

// 4. 含删除的 push → L2 审批挂起,定义不落库
→ tools/call project_push { projectId:12, files:{…少了一个任务…}, baseline:"c3d4…" }
← { outcome:"PENDING_APPROVAL", approvalId:88 }   // 人工在审批入口批准后才执行

// 5. 触发受控运行 + 读日志闭环诊断
→ tools/call task_rerun { instanceId:"…" }         // 经闸门+审计
→ tools/call instance_logs { instanceId:"…" }      // 读日志快照
```

## 验证要点(对齐 SC)

| 检查 | 期望 | SC |
|---|---|---|
| tools/list | 0 个 AI 残留工具、0 个 create_task | SC-001 |
| 纯新增/更新 push | L1 EXECUTED + 审计 | SC-002 |
| 含删除/force push | L2 PENDING,批准前 0 落库 + 审计 | SC-002 |
| 跨租户调 query_*/project_* | 100% 拒,0 泄漏 | SC-003 |
| project_push(MCP) vs C 直调 | 同语义、round-trip 一致 | SC-004 |
| 只读 vs REST | 同源口径 | SC-005 |
| ops/metrics/日志/DAG 端点 | 行为不回归 | SC-006 |

## 测试落点

- `McpToolsListNoLegacyTest`:tools/list 断言无 create_task、无 query_diagnosis 类。
- `McpProjectPushGateTest`:L1 直通落库+审计;构造含删除 → L2 PENDING + 断言 0 落库;无效定义 → `project.sync.*` 拒、不部分落库。
- `McpTenantIsolationTest`:租户 A token 调 query_*/project_* 取租户 B 资源 → 拒(含既有 query_* 回补)。
- `McpReadSameSourceTest`:只读工具与对应 REST 抽样同源。
- **回归**:复用 C 的 `ProjectSyncService` 测试 + ops 观测端点不回归。
- **禁**:`_skipped`/注释 `@Test`;真跑用 `-Dmaven.build.cache.enabled=false`。
