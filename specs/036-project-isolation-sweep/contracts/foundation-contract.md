# 地基契约 (Foundation Contract) — ✅ 已落地并冻结（收尾方 2026-07-01）

> 收尾方已实现并冻结。四路 agent **只消费此契约、不修改地基文件**。
> 实际落地与最初草案有两处修正（见文末「落地修正」）：**ProjectScope 落 master 作 `@Service`（非 api 静态）**；**校验用显式参数**。

## 后端 API（稳定面）

```java
// 1) 请求上下文：projectId 已贯通
//    com.dataweave.api.infrastructure.TenantContext（api 控制器/MCP 读）
Long TenantContext.tenantId();     // 已有
Long TenantContext.userId();       // 已有
Long TenantContext.projectId();    // ✅ 新增：当前请求项目 id（可能 null）
//    exchange 属性 "projectId"（alert 等 alert-模块控制器读，与既有 "tenantId" 属性并列）

// 2) 项目作用域校验：com.dataweave.master.application.ProjectScope —— @Service（注入使用）
Long    require(Long tenantId, Long userId, Long projectId);  // 成员校验；缺失→project.required；非成员→project.forbidden(403)；通过返回 projectId
boolean isMember(Long tenantId, Long userId, Long projectId); // 软校验不抛异常（供菜单/视图可见性）
```

**各模块如何取 projectId 并校验：**
- **api 控制器**（Ops/Metrics/Lineage…）：`Long pid = projectScope.require(TenantContext.tenantId(), TenantContext.userId(), TenantContext.projectId());` 然后按 `pid` 过滤，或透传给 master 服务。
- **alert 控制器**：从 `exchange` 属性取 `tenantId`/`userId`/`projectId`（沿用既有 `exchange` 读身份方式），调注入的 `projectScope.require(...)`。
- **master 服务**（OpsService/MetricService/LineageService）：由其控制器**透传 projectId 作方法参数**，服务内 `... AND project_id = ?` 过滤。**master 不反向依赖 api 的 ThreadLocal**。

- **注入时机**：`JwtAuthFilter`（`/api/**`）解析 projectId（`X-Project-Id` 头优先，`?projectId=` 查询兜底——SSE/EventSource 友好），置入 `TenantContext` + `exchange` 属性，请求结束 `doFinally` clear。MCP 路径 projectId 为 null（MCP 无运行时项目选择，租户隔离不变）。
- **Repository 约定**：隔离查询方法命名 `findBy...AndProjectId(...)` 或 `findByTenantIdAndProjectId...`；四路各自在所属模块补方法。
- **角色解析（roleOf）**：不在地基内——由 **D 路**基于 `project_member.role_id` + `roles` 表实现（US4 T047）。四路读数据行隔离只需 `require`/`isMember`。

## 错误码（收尾方接入 GlobalExceptionHandler + 双语）

| code | 语义 | HTTP 表现 |
|------|------|-----------|
| `project.required` | 请求缺当前项目身份 | 结构化 err，非 500 |
| `project.forbidden` | projectId 不属当前租户/用户非成员 | 结构化 err |
| `project.role.forbidden` | 项目角色权限不足（US4 写操作） | 结构化 err，不弱化闸门 |

## 前端约定（稳定面）

```ts
import { useProjectContext } from "@/lib/project-context";
const projectId = useProjectContext.getState().currentProjectId(); // 受隔离请求统一附带
```

- **传输形态已冻结**：受隔离请求附 **`X-Project-Id` 请求头**（优先）或 **`?projectId=` 查询参数**（GET/SSE 便利，与 catalog/datasource 既有约定一致）。二者后端都认。
- 详情 tab `params` 必须透传 `projectId`；切项目失效 tab 行为复用 032。

## 落地修正（相对最初草案）
1. **ProjectScope 从 api 迁到 master**：最初草案放 api/静态，但 `OpsService`/`MetricService`/`LineageService`（master）、`AlertController`（alert）**无法 import api**（依赖方向 api→master）。故改为 `com.dataweave.master.application.ProjectScope` `@Service`，四模块均可注入。
2. **成员校验从"桩"补成真校验**：外部 agent 初版 `require()` 仅非空判断（`// TODO 收尾方补`），存在**同租户内越权看他项目**的安全空洞。已用 `project_member` 表真校验（`countByTenantIdAndProjectIdAndUserIdAndDeleted > 0`），非成员 → `project.forbidden(403)`。测试：`ProjectScopeTest`。
3. 校验入口改**显式参数** `require(tenantId,userId,projectId)`，避免 master 依赖 api ThreadLocal，逻辑更纯、可 mock 测。

## 冻结声明
契约冻结后，四路对上述签名视为不变量。若需扩展 → 回报收尾方，不得自行修改 `TenantContext.java`/`JwtAuthFilter.java`/`ProjectScope.java`/`lib/project-context.ts`。
