# 036-D 角色/菜单隔离 — 剩余收口实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 FR-042 的后端写端点项目角色授权从「仅 ProjectController」扩展到任务/工作流/指标市场/审批/push 全覆盖，并完成 T054 浏览器三角色验证与文档收口。

**Architecture:** 复用已落地的 `ProjectRoleService.requirePermission`（master @Service，显式三元组参数）。在 api 模块加一个薄门面 `ProjectAuthz`（读 `TenantContext` + 委托 ProjectRoleService），各写端点在进入业务/闸门**之前**调用：by-id 端点按**实体归属 projectId** 授权（防跨项目按 id 改删，镜像 T045b `AlertController.requireOwned` 模式），create 类端点按**当前请求项目**（`TenantContext.projectId()`，来自 `X-Project-Id`）授权并落库打戳。授权是闸门前置门，通过后照常走 `GatedActionService`/`PolicyEngine`，零 bypass、不弱化。

**Tech Stack:** Java 25 / Spring Boot 4 WebFlux、JUnit 5 + WebTestClient（H2 profile）、前端已无代码缺口（vitest/typecheck 验证 + Playwright 浏览器门）。

## ⚠️ 现状基线（2026-07-02 盘点，与 tasks.md「D 未开始」不符——D 已大半落地于 main）

| 任务 | 状态 | 证据 |
|------|------|------|
| T045 后端角色授权测试 | ✅（但只覆盖 /me + 成员管理） | `dataweave-api/src/test/java/com/dataweave/api/ProjectRoleAuthzTest.java`（8 用例） |
| T046 前端 vitest 三角色菜单 | ✅ | `frontend/lib/workspace/nav-permissions.test.ts` |
| T047 角色/权限集解析 | ✅ | `ProjectRoleService.java`（master）+ `ProjectRoleServiceTest`（10 用例） |
| T048 写端点授权 | **❗仅 ProjectController**（update/delete/members） | Task/Workflow/MetricMarketplace/Approval/ProjectSync push **未接** ← 本计划主体 |
| T049 前端权限查询 | ✅ | `GET /api/projects/{id}/me` + `frontend/lib/project-permissions.ts` |
| T050 left-nav 过滤 | ✅ | `left-nav.tsx` canView + 整组隐藏 |
| T051 视图级守卫 | ✅ | `views.ts` requirePermission 声明 + `workspace.tsx` denied 遮罩 + `tab-bar.tsx` 过滤 |
| T052 切项目重算 | ✅ | `syncProjectPermissions()` 订阅 currentProjectId |
| T053 i18n | ✅ | `messages/{zh-CN,en-US}.json` `permission` 命名空间键集一致 |
| T054 浏览器验证 | ❌ | 本计划 Task 6 |

**角色映射（已冻结，勿新造）**：seed 既定 ADMIN≈OWNER、DEVELOPER≈EDITOR、VIEWER≈VIEWER；权限码 `task:manage` / `workflow:manage` / `metric:manage` / `datasource:manage` / `project:manage`（ADMIN 5 权、DEVELOPER 4 权、VIEWER 0 权）。

## Global Constraints

- **worktree 隔离（硬约束）**：`git worktree add ../dw-036-d -b 036-iso-d`，全部工作在 `../dw-036-d` 进行，绝不在主副本改代码。
- **不碰地基文件**：`TenantContext.java` / `JwtAuthFilter.java` / `McpAuthFilter.java` / `frontend/lib/project-context.ts` 只读不改；契约不满足回报收尾方。
- **不碰兄弟路冲突面**：A=OpsService/OpsController/ops panels；B=MetricService/MetricsController/LineageService；C=alert/quality/schema.sql。`MetricMarketplaceController` 归 D（B 的面是 `MetricsController`）。
- **错误码已存在，直接复用**：`project.required` / `project.forbidden`(403) / `project.role.forbidden`(403)，经 `GlobalExceptionHandler` 本地化；契约响应形如 HTTP 200 + `$.code=403` + `$.errorCode=project.role.forbidden`。
- **不弱化闸门**：授权在 `gatedActionService.submit` 之前；通过后闸门照常执行。
- **每次编辑后**：`cd backend && ./mvnw -q -pl <module> compile`；前端 `pnpm typecheck`。
- **长跑命令 WSL2 必须 setsid 脱离**：`setsid bash -c 'cd backend && ./mvnw -pl dataweave-api,dataweave-master test >/tmp/claude-1000/.../d.log 2>&1; echo $? >/tmp/.../d.exit' </dev/null >/dev/null 2>&1 & disown`，单次瞬时轮询，不写 sleep 循环。
- **测试基线**：H2 profile（`@ActiveProfiles("h2")`）、`@SpringBootTest(RANDOM_PORT)` + WebTestClient + `JwtUtil.generate`；seed：project 1、user1=ADMIN、user2=DEVELOPER；VIEWER/outsider 由测试造（模式照抄 `ProjectRoleAuthzTest.setUp`，含 IDENTITY RESTART 技巧）。
- 提交信息以 `feat(036-d):` / `fix(036-d):` 前缀，小步频提。

---

### Task 1: `ProjectAuthz` 门面 + TaskController 写端点授权（task:manage）

**Files:**
- Create: `backend/dataweave-api/src/main/java/com/dataweave/api/infrastructure/ProjectAuthz.java`
- Modify: `backend/dataweave-api/src/main/java/com/dataweave/api/interfaces/TaskController.java`
- Modify: `backend/dataweave-master/src/main/java/com/dataweave/master/application/TaskService.java:77-91`（create 打戳改 default-if-null）
- Test: `backend/dataweave-api/src/test/java/com/dataweave/api/TaskRoleAuthzTest.java`

**Interfaces:**
- Consumes: `ProjectRoleService.requirePermission(Long tenantId, Long userId, Long projectId, String permission)`（已存在）；`TenantContext.tenantId()/userId()/projectId()`（地基，只读）；`TaskService.getById(Long) → Optional<TaskDetail>`，`TaskDetail.task().getProjectId()`。
- Produces: `ProjectAuthz.require(String permission, Long projectId)` 与 `ProjectAuthz.requireCurrent(String permission)`——Task 2/3 的控制器直接注入复用。

- [ ] **Step 1: 写失败测试**（新文件 `TaskRoleAuthzTest.java`；setUp/clientFor 结构照抄 `ProjectRoleAuthzTest.java:55-96`，此处只列测试方法体）

```java
package com.dataweave.api;

import com.dataweave.api.infrastructure.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 036-D 任务定义写端点项目角色授权（FR-042：任务定义增删改/上下线 = EDITOR+）。
 * seed：project 1、user1=ADMIN、user2=DEVELOPER；user3=VIEWER 由 setUp 造（同 ProjectRoleAuthzTest）。
 * 另造 project 2（user2 非其成员）+ 归属 project 2 的 task，验证跨项目 by-id 守卫。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
class TaskRoleAuthzTest {

    private static final long TENANT = 1L;
    private static final long PROJECT = 1L;

    @LocalServerPort int port;
    @Autowired JwtUtil jwtUtil;
    @Autowired JdbcTemplate jdbc;

    WebTestClient devClient;     // user2 = DEVELOPER（task:manage 持有）
    WebTestClient viewerClient;  // user3 = VIEWER（无权限）

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM project_member WHERE user_id = 3 AND project_id = ?", PROJECT);
        jdbc.update("DELETE FROM users WHERE id = 3");
        jdbc.update("INSERT INTO users (id, tenant_id, username, password_hash, display_name, status, "
                + "created_by, updated_by, created_at, updated_at, deleted, version) VALUES "
                + "(3, 1, 'viewer', '{plain}viewer', 'viewer', 'ACTIVE', 1, 1, "
                + "TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0)");
        jdbc.update("INSERT INTO project_member (id, tenant_id, project_id, user_id, role_id, "
                + "created_by, updated_by, created_at, updated_at, deleted, version) VALUES "
                + "(200, 1, ?, 3, 3, 1, 1, TIMESTAMP '2026-06-01 00:00:00', "
                + "TIMESTAMP '2026-06-01 00:00:00', 0, 0)", PROJECT);
        jdbc.execute("ALTER TABLE project_member ALTER COLUMN id RESTART WITH 100000");
        // project 2（user2 非成员）+ 其名下 task_def id=9001
        jdbc.update("DELETE FROM task_def WHERE id = 9001");
        jdbc.update("DELETE FROM projects WHERE id = 2");
        jdbc.update("INSERT INTO projects (id, tenant_id, code, name, owner_id, status, created_by, "
                + "updated_by, created_at, updated_at, deleted, version) VALUES "
                + "(2, 1, 'p2', 'project-2', 1, 'ACTIVE', 1, 1, TIMESTAMP '2026-06-01 00:00:00', "
                + "TIMESTAMP '2026-06-01 00:00:00', 0, 0)");
        jdbc.update("INSERT INTO task_def (id, tenant_id, project_id, name, type, content, status, "
                + "current_version_no, has_draft_change, retry_max, priority, created_at, updated_at, "
                + "deleted, version) VALUES (9001, 1, 2, 'p2-task', 'PYTHON', 'print(1)', 'DRAFT', "
                + "0, 1, 0, 5, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0)");
        jdbc.execute("ALTER TABLE projects ALTER COLUMN id RESTART WITH 100000");
        jdbc.execute("ALTER TABLE task_def ALTER COLUMN id RESTART WITH 100000");

        devClient = clientFor(2L, "developer");
        viewerClient = clientFor(3L, "viewer");
    }

    private WebTestClient clientFor(long userId, String name) {
        String bearer = "Bearer " + jwtUtil.generate(userId, TENANT, name, List.of("ADMIN"));
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", bearer)
                .defaultHeader("X-Project-Id", String.valueOf(PROJECT))
                .build();
    }

    @Test
    void viewer_cannot_create_task_role_forbidden() {
        viewerClient.post().uri("/api/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "authz-task-v", "type", "PYTHON", "content", "print(1)"))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(403)
                .jsonPath("$.errorCode").isEqualTo("project.role.forbidden");
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_def WHERE name = 'authz-task-v'", Integer.class);
        assertThat(cnt).isEqualTo(0);
    }

    @Test
    void developer_can_create_task_and_project_is_stamped() {
        devClient.post().uri("/api/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "authz-task-d", "type", "PYTHON", "content", "print(1)"))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.projectId").isEqualTo(1); // 落库打当前项目戳，非硬编码
    }

    @Test
    void developer_cannot_update_task_of_other_project_forbidden() {
        // user2 是 project 1 的 DEVELOPER，但 task 9001 归属 project 2 → 按实体归属拒绝
        devClient.put().uri("/api/tasks/9001")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "hacked", "type", "PYTHON", "content", "print(2)"))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(403)
                .jsonPath("$.errorCode").isEqualTo("project.forbidden");
    }

    @Test
    void viewer_cannot_delete_task_role_forbidden() {
        // 先由 developer 造一个 project 1 的任务
        byte[] resp = devClient.post().uri("/api/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "authz-task-del", "type", "PYTHON", "content", "print(1)"))
                .exchange().expectBody().returnResult().getResponseBody();
        Long id = jdbc.queryForObject(
                "SELECT id FROM task_def WHERE name = 'authz-task-del'", Long.class);
        viewerClient.delete().uri("/api/tasks/{id}", id)
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(403)
                .jsonPath("$.errorCode").isEqualTo("project.role.forbidden");
    }

    @Test
    void create_without_project_header_rejected_required() {
        // 不带 X-Project-Id 的写请求 → project.required（越权探测：省略头不能绕过）
        String bearer = "Bearer " + jwtUtil.generate(2L, TENANT, "developer", List.of("ADMIN"));
        WebTestClient bare = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", bearer).build();
        bare.post().uri("/api/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "authz-task-x", "type", "PYTHON", "content", "print(1)"))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("project.required");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
setsid bash -c 'cd backend && ./mvnw -q -pl dataweave-api test -Dtest=TaskRoleAuthzTest >/tmp/claude-1000/-home-wushengzhou-workspace-github-data-weave/9504ff7c-8b58-4103-9436-8143edae813b/scratchpad/t1.log 2>&1; echo $? >/tmp/claude-1000/-home-wushengzhou-workspace-github-data-weave/9504ff7c-8b58-4103-9436-8143edae813b/scratchpad/t1.exit' </dev/null >/dev/null 2>&1 & disown
```
预期：FAIL——create 无授权（VIEWER 也返回 code=0）、projectId 落库为 1 的断言在 `developer_can_create_task_and_project_is_stamped` 恰好假通过但 `viewer_cannot_create_task_role_forbidden` 必红。

- [ ] **Step 3: 新建 `ProjectAuthz.java`**

```java
package com.dataweave.api.infrastructure;

import com.dataweave.master.application.ProjectRoleService;
import org.springframework.stereotype.Component;

/**
 * 036-D 控制器层项目角色授权门面（FR-042）。
 *
 * <p>封装「TenantContext 身份 + 目标 projectId + {@link ProjectRoleService#requirePermission}」，
 * 供 Task/Workflow/MetricMarketplace/Approval/ProjectSync 写端点复用。语义（与 ProjectScope 对齐）：
 * 身份/项目缺失 → {@code project.required}；非成员 → {@code project.forbidden}(403)；
 * 角色权限不足 → {@code project.role.forbidden}(403)。
 *
 * <p>本门面是闸门前置门：通过后调用方照常走 GatedActionService/PolicyEngine，零 bypass。
 * by-id 端点应传<b>实体归属 projectId</b>（防跨项目按 id 改删，镜像 AlertController.requireOwned）；
 * create 类端点用 {@link #requireCurrent}（当前请求项目，来自 X-Project-Id / ?projectId=）。
 */
@Component
public class ProjectAuthz {

    private final ProjectRoleService projectRoleService;

    public ProjectAuthz(ProjectRoleService projectRoleService) {
        this.projectRoleService = projectRoleService;
    }

    /** 按显式 projectId 授权（by-id 端点用实体归属项目）。 */
    public void require(String permission, Long projectId) {
        projectRoleService.requirePermission(
                TenantContext.tenantId(), TenantContext.userId(), projectId, permission);
    }

    /** 按当前请求项目授权（create 类端点）。 */
    public void requireCurrent(String permission) {
        require(permission, TenantContext.projectId());
    }
}
```

- [ ] **Step 4: TaskController 接入**（注入 `ProjectAuthz projectAuthz`（构造器加参），写端点授权 + create 打戳）

```java
// 构造器新增参数 ProjectAuthz projectAuthz 并赋值 this.projectAuthz = projectAuthz;

/** 创建任务草稿。036-D：EDITOR+（task:manage），并按当前项目打戳（FR-042）。 */
@PostMapping
public ApiResponse<TaskDef> create(@RequestBody TaskDef body) {
    projectAuthz.requireCurrent("task:manage");
    body.setTenantId(TenantContext.tenantId());
    body.setProjectId(TenantContext.projectId());
    return ApiResponse.ok(taskService.create(body));
}

// by-id 写端点（update/softDelete/publish/offline/rollback/assignCatalog）各自方法体首行加：
//     requireTaskManage(id);
// 文件尾部加私有方法：

/**
 * 036-D：任务定义写操作授权（FR-042 EDITOR+）。按实体归属 projectId 授权，
 * 防跨项目按 id 改删；任务不存在时保持既有 404 语义。
 */
private void requireTaskManage(Long taskId) {
    Long pid = taskService.getById(taskId)
            .map(d -> d.task().getProjectId())
            .orElseThrow(() -> new BizException("task.not_found", taskId).withHttpStatus(404));
    projectAuthz.require("task:manage", pid);
}
```

接入清单（每个方法体第一行）：`update`(PUT /{id})、`softDelete`(DELETE /{id})、`publish`、`offline`、`rollback`、`assignCatalog`(PATCH /{id}/catalog)。**不接**：`/{id}/run`、`/preview-params`（运行/只读，非定义编辑，由 PolicyEngine 闸门管——记入接缝清单）。

- [ ] **Step 5: `TaskService.create` 打戳改 default-if-null**（`TaskService.java:79-80`）

```java
// 原：task.setTenantId(1L); task.setProjectId(1L);
if (task.getTenantId() == null) task.setTenantId(1L);
if (task.getProjectId() == null) task.setProjectId(1L);
```

（保底 1L 保留：CLI push 路径经 ProjectSyncService 自带 projectId；直接调 service 的旧内部路径不破坏。）

- [ ] **Step 6: 编译 + 跑测试通过**

```bash
cd backend && ./mvnw -q -pl dataweave-master compile && ./mvnw -q -pl dataweave-api compile
# 然后 setsid 跑 TaskRoleAuthzTest（同 Step 2 命令），预期 exit=0、5/5 通过
```

- [ ] **Step 7: Commit**

```bash
git add backend/dataweave-api/src/main/java/com/dataweave/api/infrastructure/ProjectAuthz.java \
        backend/dataweave-api/src/main/java/com/dataweave/api/interfaces/TaskController.java \
        backend/dataweave-master/src/main/java/com/dataweave/master/application/TaskService.java \
        backend/dataweave-api/src/test/java/com/dataweave/api/TaskRoleAuthzTest.java
git commit -m "feat(036-d): 任务定义写端点接入项目角色授权（task:manage）+ ProjectAuthz 门面"
```

---

### Task 2: WorkflowController 写端点授权（workflow:manage）

**Files:**
- Modify: `backend/dataweave-api/src/main/java/com/dataweave/api/interfaces/WorkflowController.java`
- Test: `backend/dataweave-api/src/test/java/com/dataweave/api/WorkflowRoleAuthzTest.java`

**Interfaces:**
- Consumes: Task 1 的 `ProjectAuthz.require/requireCurrent`；`WorkflowService.getById(Long) → Optional<WorkflowDetail>`，`WorkflowDetail.workflow().getProjectId()`（record 字段名是 `workflow`，非 `task`）。
- Produces: 无新接口。

- [ ] **Step 1: 写失败测试**（结构同 `TaskRoleAuthzTest`：setUp 造 viewer + project 2 + 归属 project 2 的 `workflow_def` 行 id=9002；字段以 `schema.sql` 的 `workflow_def` DDL 为准）。测试方法（4 个）：

```java
@Test
void viewer_cannot_create_workflow_role_forbidden() {
    viewerClient.post().uri("/api/workflows")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("name", "authz-wf-v"))
            .exchange().expectStatus().isOk()
            .expectBody()
            .jsonPath("$.code").isEqualTo(403)
            .jsonPath("$.errorCode").isEqualTo("project.role.forbidden");
}

@Test
void developer_can_create_workflow() {
    devClient.post().uri("/api/workflows")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("name", "authz-wf-d"))
            .exchange().expectStatus().isOk()
            .expectBody()
            .jsonPath("$.code").isEqualTo(0);
}

@Test
void developer_cannot_save_dag_of_other_project_forbidden() {
    devClient.put().uri("/api/workflows/9002/dag")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("nodes", List.of(), "edges", List.of()))
            .exchange().expectStatus().isOk()
            .expectBody()
            .jsonPath("$.code").isEqualTo(403)
            .jsonPath("$.errorCode").isEqualTo("project.forbidden");
}

@Test
void viewer_cannot_publish_workflow_role_forbidden() {
    // devClient 先建 workflow 取 id，viewerClient POST /{id}/publish → project.role.forbidden
    devClient.post().uri("/api/workflows").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("name", "authz-wf-pub")).exchange();
    Long id = jdbc.queryForObject(
            "SELECT id FROM workflow_def WHERE name = 'authz-wf-pub'", Long.class);
    viewerClient.post().uri("/api/workflows/{id}/publish", id)
            .exchange().expectStatus().isOk()
            .expectBody()
            .jsonPath("$.code").isEqualTo(403)
            .jsonPath("$.errorCode").isEqualTo("project.role.forbidden");
}
```

- [ ] **Step 2: 跑测试确认失败**（setsid，同 Task 1 模式，`-Dtest=WorkflowRoleAuthzTest`）
- [ ] **Step 3: WorkflowController 接入**

```java
// 构造器注入 ProjectAuthz projectAuthz。

// create（POST /api/workflows）方法体首行：
projectAuthz.requireCurrent("workflow:manage");
// 若 WorkflowService.create 也有 1L/1L 硬编码打戳，按 Task 1 Step 5 同款 default-if-null 修正，
// 并在 create 里 body.setTenantId(TenantContext.tenantId()); body.setProjectId(TenantContext.projectId());

// by-id 写端点各自方法体首行加 requireWorkflowManage(id)：
// update(PUT /{id})、softDelete(DELETE /{id})、offline、rollback、
// saveDag(PUT /{id}/dag)、saveDraft(PUT /{id}/draft)、publish(POST /{id}/publish)、
// addDependency(POST /{id}/dependencies)、removeDependency(DELETE /{id}/dependencies/{depId})、
// assignCatalog(PATCH /{id}/catalog)

/** 036-D：工作流定义写操作授权（FR-042 EDITOR+），按实体归属 projectId。 */
private void requireWorkflowManage(Long workflowId) {
    Long pid = workflowService.getById(workflowId)
            .map(d -> d.workflow().getProjectId())
            .orElseThrow(() -> new BizException("workflow.not_found", workflowId).withHttpStatus(404));
    projectAuthz.require("workflow:manage", pid);
}
```

**不接**：`POST /{id}/run`（触发运行走闸门）、全部 GET。`workflow.not_found` 错误码若不存在，用该文件既有的 not-found 错误码（先 grep 该文件 `not_found` 对齐，勿新造键）。

- [ ] **Step 4: 编译 + 跑测试通过**（`./mvnw -q -pl dataweave-api compile` 后 setsid 跑，预期 4/4）
- [ ] **Step 5: Commit**

```bash
git add backend/dataweave-api/src/main/java/com/dataweave/api/interfaces/WorkflowController.java \
        backend/dataweave-master/src/main/java/com/dataweave/master/application/WorkflowService.java \
        backend/dataweave-api/src/test/java/com/dataweave/api/WorkflowRoleAuthzTest.java
git commit -m "feat(036-d): 工作流定义写端点接入项目角色授权（workflow:manage）"
```

---

### Task 3: 指标市场（metric:manage）+ 审批（project:manage）+ push（task:manage）

**Files:**
- Modify: `backend/dataweave-api/src/main/java/com/dataweave/api/interfaces/MetricMarketplaceController.java`
- Modify: `backend/dataweave-api/src/main/java/com/dataweave/api/interfaces/ApprovalController.java`
- Modify: `backend/dataweave-api/src/main/java/com/dataweave/api/interfaces/ProjectSyncController.java`
- Test: `backend/dataweave-api/src/test/java/com/dataweave/api/GovernanceRoleAuthzTest.java`

**Interfaces:**
- Consumes: `ProjectAuthz`（Task 1）。
- Produces: 无新接口。`MetricMarketplaceController` 写端点的 `@RequestParam(defaultValue = "1") Long projectId` 改为 `required = false` + null 时回落 `TenantContext.projectId()`（对齐收尾方 T056 在 AssetCatalogController 的 resolveProjectId 模式——去硬编码默认 1）。

- [ ] **Step 1: 写失败测试**（setUp 同前；6 个用例）

```java
@Test
void viewer_cannot_list_metric_role_forbidden() {
    viewerClient.post().uri("/api/marketplace/metrics?projectId=1")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("metricCode", "m1"))
            .exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.errorCode").isEqualTo("project.role.forbidden");
}

@Test
void developer_can_list_metric_gate_still_applies() {
    // DEVELOPER 有 metric:manage → 授权放行；随后进入闸门（GateResult 正常返回，闸门语义不弱化）
    devClient.post().uri("/api/marketplace/metrics?projectId=1")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("metricCode", "m2"))
            .exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.code").isEqualTo(0);
}

@Test
void viewer_cannot_certify_role_forbidden() {
    viewerClient.post().uri("/api/marketplace/metrics/1/certify?projectId=1")
            .exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.errorCode").isEqualTo("project.role.forbidden");
}

@Test
void developer_cannot_approve_project_manage_only() {
    // 审批 = OWNER only（project:manage）；DEVELOPER 无此权限
    devClient.post().uri("/api/approvals/1/approve")
            .exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.errorCode").isEqualTo("project.role.forbidden");
}

@Test
void admin_approve_reaches_service() {
    // ADMIN（user1）授权放行；审批单不存在时进入既有业务错误而非 project.*（证明授权层放行）
    adminClient.post().uri("/api/approvals/999999/approve")
            .exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.errorCode").value(v ->
                    org.assertj.core.api.Assertions.assertThat(String.valueOf(v))
                            .doesNotContain("project."));
}

@Test
void viewer_cannot_push_role_forbidden() {
    viewerClient.post().uri("/api/projects/1/push")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("files", List.of(), "deletes", List.of(), "force", false))
            .exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.errorCode").isEqualTo("project.role.forbidden");
}
```

（`PushCommand` 的 JSON 字段以 `ProjectSyncDtos.PushCommand` 实际 record 组件为准，写测试前先读该文件对齐。）

- [ ] **Step 2: 跑测试确认失败**（setsid，`-Dtest=GovernanceRoleAuthzTest`）
- [ ] **Step 3: 三个控制器接入**

```java
// MetricMarketplaceController —— 4 个写端点（POST /metrics、POST /metrics/{id}/certify、
// DELETE /metrics/{id}、POST /metrics/{id}/reuse）：
// ① @RequestParam(defaultValue = "1") Long projectId → @RequestParam(required = false) Long projectId
// ② 方法体首行（requireTenant() 之后）：
Long pid = projectId != null ? projectId : TenantContext.projectId();
projectAuthz.require("metric:manage", pid);
// ③ 后续 gateBase(...) 沿用 pid 替代原 projectId 参数。
// 只读端点（GET detail 等）不动。

// ApprovalController —— approve/reject 方法体首行（审批 = OWNER only，FR-042）：
// agent_action 无 project_id 列（schema 归 C 路独占，不在 D 改），按当前请求项目授权：
projectAuthz.requireCurrent("project:manage");

// ProjectSyncController —— 仅 push（pull/diff 只读不动）：
@PostMapping("/{projectId}/push")
public ApiResponse<ProjectSyncDtos.PushResult> push(@PathVariable Long projectId,
                                                    @RequestBody ProjectSyncDtos.PushCommand cmd) {
    projectAuthz.require("task:manage", projectId); // 036-D：定义写入 = EDITOR+（FR-042）
    return ApiResponse.ok(syncService.push(projectId, TenantContext.tenantId(),
            TenantContext.userId(), cmd));
}
// 注意：MCP project_push 直调 ProjectSyncService（不经本控制器），其风险自适应闸门不受影响。
```

- [ ] **Step 4: 编译 + 跑测试通过**（预期 6/6）
- [ ] **Step 5: Commit**

```bash
git add backend/dataweave-api/src/main/java/com/dataweave/api/interfaces/MetricMarketplaceController.java \
        backend/dataweave-api/src/main/java/com/dataweave/api/interfaces/ApprovalController.java \
        backend/dataweave-api/src/main/java/com/dataweave/api/interfaces/ProjectSyncController.java \
        backend/dataweave-api/src/test/java/com/dataweave/api/GovernanceRoleAuthzTest.java
git commit -m "feat(036-d): 指标市场/审批/push 写端点接入项目角色授权"
```

---

### Task 4: 全量回归 + 存量测试适配

新增授权会让「不带 X-Project-Id 的既有写端点测试」变红（T055 有先例：US1/US2 加 ProjectScope 后收尾方补头）。已知受影响面：`TaskAndFreshnessEndpointTest`、`TaskControllerRunTest`、`ManualRunTriggerTest`、`CatalogApiTest`、CLI 集成类测试（凡 POST/PUT/DELETE `/api/tasks|workflows|marketplace|approvals|projects/*/push` 的）。

**Files:**
- Modify: 上述测试类（每类只加一处 `defaultHeader("X-Project-Id", "1")`，或改用 seed user1=ADMIN 的 token）
- 不改生产代码；若某测试红因为它直调 service（不经 HTTP）则与授权无关，勿乱改。

- [ ] **Step 1: 全量跑 api+master 测试**

```bash
setsid bash -c 'cd backend && ./mvnw -pl dataweave-api,dataweave-master test >/tmp/claude-1000/-home-wushengzhou-workspace-github-data-weave/9504ff7c-8b58-4103-9436-8143edae813b/scratchpad/full.log 2>&1; echo $? >/tmp/claude-1000/-home-wushengzhou-workspace-github-data-weave/9504ff7c-8b58-4103-9436-8143edae813b/scratchpad/full.exit' </dev/null >/dev/null 2>&1 & disown
```

- [ ] **Step 2: 逐个修红**——只允许两类修法：① WebTestClient 建 client 处加 `.defaultHeader("X-Project-Id", "1")`；② 测试用非成员用户时改用 seed user1/user2。修一批重跑一批（`-Dtest=类名`）。
- [ ] **Step 3: 全量绿后 Commit**

```bash
git add -A backend/*/src/test/
git commit -m "test(036-d): 存量写端点测试补 X-Project-Id 头适配项目角色授权"
```

---

### Task 5: 前端验证（无代码改动预期）

前端权限层已全部落地（见现状基线表）。本任务只验证接缝没被后端改动打破。

- [ ] **Step 1**: `cd frontend && pnpm typecheck` → 0 错误
- [ ] **Step 2**: `cd frontend && pnpm test` → vitest 全绿（含 `nav-permissions.test.ts` 9 用例）
- [ ] **Step 3**: i18n 键集一致性抽查：`node -e "const zh=require('./messages/zh-CN.json'),en=require('./messages/en-US.json');const k=o=>JSON.stringify(Object.keys(o.permission).sort());console.log(k(zh)===k(en)?'OK':'MISMATCH')"` → OK
- [ ] **Step 4**: 若全绿无改动，本任务无 commit；有键集缺口则补键后 `git commit -m "fix(036-d): i18n permission 键集补齐"`

---

### Task 6: T054 浏览器验证（三角色菜单差异 + 切项目重算 + 越权直达被拒）

**前置**（参考 memory：后端用 setsid 脱离启动；playwright 用全局 `@playwright/test` 绝对路径 import；登录绕过须同时注入 `dw.auth.token` + `dw.auth.user`）：

- [ ] **Step 1: 起后端（H2）+ 前端**

```bash
setsid bash -c 'cd backend && ./mvnw -pl dataweave-api spring-boot:run -Dspring-boot.run.profiles=h2 >/tmp/claude-1000/-home-wushengzhou-workspace-github-data-weave/9504ff7c-8b58-4103-9436-8143edae813b/scratchpad/be.log 2>&1' </dev/null >/dev/null 2>&1 & disown
setsid bash -c 'cd frontend && pnpm dev >/tmp/claude-1000/-home-wushengzhou-workspace-github-data-weave/9504ff7c-8b58-4103-9436-8143edae813b/scratchpad/fe.log 2>&1' </dev/null >/dev/null 2>&1 & disown
# 就绪探测：curl -s localhost:8000/api/health && curl -s -o /dev/null -w '%{http_code}' localhost:4000
```

- [ ] **Step 2: 造 VIEWER 用户**（seed 只有 admin/analyst；用 admin 的 API 造 viewer + 加成员 role_id=3）

```bash
TOK=$(curl -s localhost:8000/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | jq -r .data.token)   # 密码以 data.sql seed 为准
curl -s localhost:8000/api/users -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' \
  -d '{"username":"viewer1","password":"viewer123","displayName":"viewer1"}'
VID=$(curl -s 'localhost:8000/api/users?keyword=viewer1' -H "Authorization: Bearer $TOK" | jq -r '.data.items[0].id // .data[0].id')
curl -s localhost:8000/api/projects/1/members -H "Authorization: Bearer $TOK" -H "X-Project-Id: 1" \
  -H 'Content-Type: application/json' -d "{\"userId\":$VID,\"roleId\":3}"
```

- [ ] **Step 3: Playwright 断言**（脚本放 session scratchpad，勿入 git；每角色登录后断言）：
  - **admin**：左侧导航可见「任务流编排 / 类目 / 指标市场 / 数据源 / 系统设置」全部入口；
  - **viewer1**：上述 5 个写入口**均不渲染**，ops/metrics/lineage/alerts 等只读入口可见；深链 `http://localhost:4000/?open=workflow-canvas` 打开后显示 `permission.deniedTitle`（"权限不足"）遮罩而非画布；
  - **切项目重算**：若 seed 只有 1 个项目，用 admin API 造 project 2（viewer1 不加成员）→ admin 前端切到 project 2 断言菜单重算请求（`/api/projects/2/me`）发出且导航仍全量；viewer1 无 project 2 则跳过切换断言并记录。
- [ ] **Step 4: 截图存 scratchpad + 记录结果**；杀掉两个 setsid 进程（`pkill -f spring-boot:run; pkill -f "pnpm dev"` 在各自会话内）。

---

### Task 7: 文档收口 + 接缝回报

**Files:**
- Modify: `specs/036-project-isolation-sweep/tasks.md`（Phase 6 勾选）
- Modify: `specs/036-project-isolation-sweep/sc-001-isolation-inventory.md`（写端点授权行）

- [ ] **Step 1**: tasks.md Phase 6 更新：T045~T053 勾 ✅（T045 注明「已扩展覆盖 task/workflow/marketplace/approval/push」）；T054 按 Task 6 实测结果勾选。
- [ ] **Step 2**: sc-001 清单追加/更新 D 行：`POST|PUT|DELETE /api/tasks*`、`/api/workflows*`、`/api/marketplace/metrics*`、`/api/approvals/*`、`/api/projects/{id}/push` → 「本次收口（角色授权）」，各行标注对应测试类。
- [ ] **Step 3**: 接缝遗留清单（写入 tasks.md Phase 6 尾注，回报收尾方）：
  - `agent_action` 无 `project_id` 列 → 审批授权暂按请求头项目；补列属 C 路 schema 独占面；
  - `/{id}/run`（task/workflow）与 ops 运维动作（rerun/kill）未接角色授权，由 PolicyEngine 闸门管——若产品要求 VIEWER 禁 run，另立任务；
  - `RoleController`/`UserController`/`TenantController` 为租户级系统管理，不在项目角色矩阵内（FR-042 未覆盖）；
  - 血缘 `recordTaskIo` 内部 1L/1L 占位未动（B 路/收尾方面）。
- [ ] **Step 4: Commit + 合并准备**

```bash
git add specs/036-project-isolation-sweep/tasks.md specs/036-project-isolation-sweep/sc-001-isolation-inventory.md
git commit -m "docs(036-d): D 路收口——tasks 勾选 + sc-001 授权行 + 接缝清单"
```

合并顺序遵守 tasks.md：C 路 schema 已在 main → D 可直接向 main 发起合并；合并前在 worktree 内 `git merge main` 重跑 Task 4 全量测试。

---

## Self-Review 结论

- **Spec 覆盖**：FR-040（解析）✅已落地；FR-041（菜单/视图）✅已落地+Task 5/6 验证；FR-042（写授权）→ Task 1/2/3 补全（任务/工作流/指标定义=EDITOR+；成员/设置=已落地；审批=Task 3）；FR-043（切项目重算）✅已落地+Task 6 验证；FR-050（i18n）✅已落地+Task 5 校验。US4 三条 AC 分别由 Task 6、Task 1-3 测试、Task 6 覆盖。
- **类型一致性**：`TaskDetail.task()` / `WorkflowDetail.workflow()`（record 字段名已实测确认）；`ProjectAuthz.require(String,Long)` 签名 Task 1 定义、Task 2/3 一致消费。
- **已知风险**：Task 4 存量测试红的规模未知（估 4-8 个类）；`PushCommand`/seed 密码等以实际文件为准的点已在对应步骤标注「先读文件对齐」。
