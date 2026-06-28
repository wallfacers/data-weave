package com.dataweave.api;

import com.dataweave.api.infrastructure.TenantContext;
import com.dataweave.master.application.ProjectSyncDtos;
import com.dataweave.master.application.ProjectSyncService;
import com.dataweave.master.domain.*;
import com.dataweave.master.i18n.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 深度 round-trip 完整性（SC-001）：验证 push 落库时 workflow 节点→任务绑定、
 * 数据源、版本快照真正存活,而不仅是文件名 keySet 存活。
 *
 * <p>push 是 mutator，与同配置 MOCK 测试类共享缓存上下文+内存库；用
 * {@link DirtiesContext.ClassMode#BEFORE_CLASS} 开类前重建上下文隔离前序污染。
 */
@SpringBootTest
@ActiveProfiles("h2")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class ProjectSyncRoundTripIntegrityTest {

    @Autowired ProjectSyncService syncService;
    @Autowired ProjectRepository projectRepo;
    @Autowired TaskDefRepository taskRepo;
    @Autowired WorkflowDefRepository workflowRepo;
    @Autowired WorkflowNodeRepository nodeRepo;
    @Autowired TaskDefVersionRepository taskVersionRepo;
    @Autowired DatasourceRepository datasourceRepo;

    @BeforeEach
    void setUp() {
        TenantContext.set(1L, 1L, "admin");
    }

    /** 把 project 1 的真实 bundle push 进一个全新空项目 → 全走 insert。
     *  TASK 节点的 taskId 必须解析到新插入任务的真实 id,而非被清成 null。 */
    @Test
    void freshPush_workflowNodeTaskBinding_survives() {
        // 1. pull 一个含 workflow+task 节点的真实项目
        ProjectSyncDtos.PullResult src = syncService.pull(1L, 1L);

        // 2. 新建一个空项目（tenant 1）
        Project p = new Project();
        p.setTenantId(1L);
        p.setCode("rtproof");
        p.setName("round-trip proof");
        p.setStatus("ACTIVE");
        p.setDeleted(0);
        p.setVersion(0);
        p.setCreatedAt(LocalDateTime.now());
        p.setUpdatedAt(LocalDateTime.now());
        Project saved = projectRepo.save(p);
        Long newPid = saved.getId();

        // 2b. 把 project 1 的数据源按 name 克隆进新项目(否则 datasource 名解析失败)
        for (Datasource src1 : datasourceRepo.findByProjectId(1L)) {
            Datasource d = new Datasource();
            d.setTenantId(1L);
            d.setProjectId(newPid);
            d.setName(src1.getName());
            d.setTypeCode(src1.getTypeCode());
            d.setDeleted(0);
            d.setVersion(0);
            d.setCreatedAt(LocalDateTime.now());
            d.setUpdatedAt(LocalDateTime.now());
            datasourceRepo.save(d);
        }

        // 3. push 全量到新项目（全 insert）
        ProjectSyncDtos.PushCommand cmd = new ProjectSyncDtos.PushCommand(
                src.bundle().files(), null, false, src.fileCount(), "fresh insert");
        ProjectSyncDtos.PushResult res = syncService.push(newPid, 1L, 1L, cmd);

        // 4. 新项目应有 workflow + task
        List<WorkflowDef> wfs = workflowRepo.findByProjectId(newPid);
        assertThat(wfs).as("workflow 应被插入").isNotEmpty();
        List<TaskDef> tasks = taskRepo.findByProjectId(newPid);
        assertThat(tasks).as("task 应被插入").isNotEmpty();

        // 5. 关键:每个 TASK 节点的 taskId 必须非空且指向新项目里真实存在的 task
        List<Long> realTaskIds = tasks.stream().map(TaskDef::getId).toList();
        boolean sawTaskNode = false;
        for (WorkflowDef wf : wfs) {
            for (WorkflowNode n : nodeRepo.findByWorkflowIdAndDeleted(wf.getId(), 0)) {
                if (!"VIRTUAL".equals(n.getNodeType())) {
                    sawTaskNode = true;
                    assertThat(n.getTaskId())
                            .as("TASK 节点 %s 的 taskId 不能被清成 null", n.getNodeKey())
                            .isNotNull();
                    assertThat(realTaskIds)
                            .as("TASK 节点 %s 的 taskId 必须指向新项目内真实 task", n.getNodeKey())
                            .contains(n.getTaskId());
                }
            }
        }
        assertThat(sawTaskNode).as("至少应有一个 TASK 节点参与验证").isTrue();

        // 6. 每个插入的 task 应生成版本快照(SC-004),且 status 停在草稿(不自动上线,Q1/FR-009)
        for (TaskDef t : tasks) {
            assertThat(taskVersionRepo.findByTaskIdOrderByVersionNoDesc(t.getId()))
                    .as("task %s 应有版本快照", t.getName())
                    .isNotEmpty();
            assertThat(t.getStatus())
                    .as("push 不得自动晋级 ONLINE(task %s)", t.getName())
                    .isNotEqualTo("ONLINE");
        }
        // 7. 数据源绑定存活(SC-001/FR-007):SQL 任务应有 datasourceId
        boolean anyDs = tasks.stream().anyMatch(t -> t.getDatasourceId() != null);
        assertThat(anyDs).as("至少一个任务的数据源绑定应被解析落库").isTrue();
    }

    /** D6 删除在线引用守卫:推一个删除了"被 ONLINE 工作流引用的任务"的 bundle → 整单拒。 */
    @Test
    void push_deleteTaskReferencedByOnlineWorkflow_rejected() {
        // 新建空项目 + 直接构造服务器态:task T(DRAFT)+ workflow W(ONLINE)+ node 引用 T
        Project p = new Project();
        p.setTenantId(1L); p.setCode("delguard"); p.setName("del guard");
        p.setStatus("ACTIVE"); p.setDeleted(0); p.setVersion(0);
        p.setCreatedAt(LocalDateTime.now()); p.setUpdatedAt(LocalDateTime.now());
        Long pid = projectRepo.save(p).getId();

        TaskDef t = new TaskDef();
        t.setTenantId(1L); t.setProjectId(pid); t.setName("被引用任务"); t.setType("SQL");
        t.setStatus("DRAFT"); t.setDeleted(0); t.setVersion(0L);
        t.setCreatedAt(LocalDateTime.now()); t.setUpdatedAt(LocalDateTime.now());
        Long tid = taskRepo.save(t).getId();

        WorkflowDef w = new WorkflowDef();
        w.setTenantId(1L); w.setProjectId(pid); w.setName("在线流"); w.setScheduleType("MANUAL");
        w.setStatus("ONLINE"); w.setDeleted(0); w.setVersion(0L);
        w.setCreatedAt(LocalDateTime.now()); w.setUpdatedAt(LocalDateTime.now());
        Long wid = workflowRepo.save(w).getId();

        WorkflowNode n = new WorkflowNode();
        n.setTenantId(1L); n.setProjectId(pid); n.setWorkflowId(wid); n.setTaskId(tid);
        n.setNodeKey("n1"); n.setNodeType("TASK"); n.setDeleted(0); n.setVersion(0L);
        n.setCreatedAt(LocalDateTime.now()); n.setUpdatedAt(LocalDateTime.now());
        nodeRepo.save(n);

        // 推一个空定义(不含该任务)→ 触发删除 → 被 ONLINE 引用 → 整单拒
        java.util.Map<String, String> empty = java.util.Map.of(
                "project.yaml", "formatVersion: 1\ncode: delguard\nname: del guard\n",
                "tags.yaml", "formatVersion: 1\ntags: []\n");
        ProjectSyncDtos.PushCommand cmd = new ProjectSyncDtos.PushCommand(empty, null, false, null, null);

        assertThatThrownBy(() -> syncService.push(pid, 1L, 1L, cmd))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("project.sync.delete_referenced");

        // 守卫触发后服务器态不变(全有或全无):task 仍在
        assertThat(taskRepo.findById(tid)).isPresent();
        assertThat(taskRepo.findById(tid).get().getDeleted()).isEqualTo(0);
    }

    /** FR-007:引用项目内不存在的数据源逻辑名 → 可定位拒绝,零落库。 */
    @Test
    void push_unknownDatasource_rejected() {
        ProjectSyncDtos.PullResult src = syncService.pull(1L, 1L);
        java.util.Map<String, String> files = new java.util.HashMap<>(src.bundle().files());
        // 把某个 SQL 任务的 datasource 改成不存在的名
        String badKey = files.keySet().stream()
                .filter(k -> k.endsWith(".task.yaml") && files.get(k).contains("datasource:"))
                .findFirst().orElseThrow();
        files.put(badKey, files.get(badKey).replaceAll("datasource: \\S+", "datasource: ghost_ds_xyz"));

        ProjectSyncDtos.PushCommand cmd = new ProjectSyncDtos.PushCommand(
                files, null, true, null, null); // force 跳过基线,聚焦数据源校验
        assertThatThrownBy(() -> syncService.push(1L, 1L, 1L, cmd))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("project.sync.unknown_datasource");
    }

    /** FR-010:改一个任务脚本后 diff 应把它列入 modified(非空),且只读不写。 */
    @Test
    void diff_modifiedTask_reportedAndReadOnly() {
        ProjectSyncDtos.PullResult src = syncService.pull(1L, 1L);
        java.util.Map<String, String> files = new java.util.HashMap<>(src.bundle().files());
        // 改一个 .sql 脚本体 → 任务内容变化 → modified
        String sqlKey = files.keySet().stream().filter(k -> k.endsWith(".sql")).findFirst().orElseThrow();
        files.put(sqlKey, files.get(sqlKey) + "\n-- changed by diff test\n");

        ProjectSyncDtos.PushCommand cmd = new ProjectSyncDtos.PushCommand(
                files, src.baseline(), false, null, null);
        ProjectSyncDtos.DiffPreview d = syncService.diff(1L, 1L, cmd);

        assertThat(d.modified()).as("改了脚本的任务应出现在 modified").isNotEmpty();

        // 只读:diff 后服务器态不变(再 diff 仍报同样 modified,且基线未变)
        ProjectSyncDtos.PullResult after = syncService.pull(1L, 1L);
        assertThat(after.baseline()).isEqualTo(src.baseline());
    }

    // ═══════════════════════════════════════════════════════════════
    // 013 slug roundtrip fidelity — CJK collision tests
    // ═══════════════════════════════════════════════════════════════

    /** US1 / SC-001: pull must not silently lose tasks due to slug collapse.
     *  Project 1 has CJK-named ECHO tasks (id 10-15) that previously all slugged to "-"
     *  and suffered silent overwrite. */
    @Test
    void pullIsLossless_withCjkCollidingNames() {
        // Count server-side tasks
        List<TaskDef> srvTasks = new ArrayList<>();
        for (TaskDef t : taskRepo.findByProjectId(1L)) {
            if (t.getDeleted() == null || t.getDeleted() == 0) srvTasks.add(t);
        }
        long dbCount = srvTasks.size();
        assertThat(dbCount).as("project 1 should have multiple tasks").isGreaterThan(5);

        ProjectSyncDtos.PullResult result = syncService.pull(1L, 1L);
        Map<String, String> files = result.bundle().files();

        // Count .task.yaml files in bundle
        long bundleTaskCount = files.keySet().stream()
                .filter(k -> k.endsWith(".task.yaml")).count();
        assertThat(bundleTaskCount)
                .as("pull must not lose any task: server=%d, bundle=%d", dbCount, bundleTaskCount)
                .isEqualTo(dbCount);

        // No collapsed "-.task.yaml" anywhere
        assertThat(files.keySet())
                .as("no task file should collapse to '-.task.yaml'")
                .noneMatch(k -> k.endsWith("/-.task.yaml") || k.equals("-.task.yaml"));

        // Every server task must have a unique file path (no overwrite)
        assertThat(bundleTaskCount).isEqualTo(dbCount);
    }

    /** US2 / SC-003 / FR-005: zero-change diff must report no differences,
     *  AND we must confirm entity count wasn't lost (not blind "一致"). */
    @Test
    void diffIsFaithful_zeroChangeReportsNoDiffAndNotBlind() {
        ProjectSyncDtos.PullResult src = syncService.pull(1L, 1L);

        // Count task files to prove no entities were lost
        long srcTaskCount = src.bundle().files().keySet().stream()
                .filter(k -> k.endsWith(".task.yaml")).count();

        ProjectSyncDtos.PushCommand cmd = new ProjectSyncDtos.PushCommand(
                src.bundle().files(), src.baseline(), false, null, null);
        ProjectSyncDtos.DiffPreview diff = syncService.diff(1L, 1L, cmd);

        assertThat(diff.added()).as("zero change must report no added").isEmpty();
        assertThat(diff.modified()).as("zero change must report no modified").isEmpty();
        assertThat(diff.removed()).as("zero change must report no removed").isEmpty();

        // Prove it's genuine "no difference", not blind (entities weren't lost)
        ProjectSyncDtos.PullResult verify = syncService.pull(1L, 1L);
        long verifyTaskCount = verify.bundle().files().keySet().stream()
                .filter(k -> k.endsWith(".task.yaml")).count();
        assertThat(verifyTaskCount)
                .as("re-pull must not lose entities after diff")
                .isEqualTo(srcTaskCount);
    }

    /** US2: delete one task file → diff must show exactly that task as removed,
     *  and not misidentify others. Uses a fresh project with no workflow refs. */
    @Test
    void diffIsFaithful_deleteOneTask_showsExactlyOneRemoved() {
        // Create a clean project with two simple tasks (no workflow refs to avoid warnings)
        Project p = new Project();
        p.setTenantId(1L); p.setCode("diff-faithful"); p.setName("diff faithful");
        p.setStatus("ACTIVE"); p.setDeleted(0); p.setVersion(0);
        p.setCreatedAt(LocalDateTime.now()); p.setUpdatedAt(LocalDateTime.now());
        Long newPid = projectRepo.save(p).getId();

        // Build a minimal bundle with two tasks
        Map<String, String> files = new LinkedHashMap<>();
        files.put("project.yaml", "formatVersion: 1\ncode: diff-faithful\nname: diff faithful\n");
        files.put("tags.yaml", "formatVersion: 1\ntags: []\n");
        files.put("task-a.task.yaml", "formatVersion: 1\nname: Task A\ntype: SHELL\ncontent: |\n  echo a\n");
        files.put("task-a.sh", "echo a\n");
        files.put("task-b.task.yaml", "formatVersion: 1\nname: Task B\ntype: SHELL\ncontent: |\n  echo b\n");
        files.put("task-b.sh", "echo b\n");

        ProjectSyncDtos.PushCommand pushCmd = new ProjectSyncDtos.PushCommand(
                files, null, false, null, null);
        syncService.push(newPid, 1L, 1L, pushCmd);

        // Pull to get stable bundle
        ProjectSyncDtos.PullResult src = syncService.pull(newPid, 1L);
        Map<String, String> pulled = new HashMap<>(src.bundle().files());

        // Remove the first .task.yaml file (and its companion script if any)
        String taskYamlKey = pulled.keySet().stream()
                .filter(k -> k.endsWith(".task.yaml")).findFirst().orElseThrow();
        String base = taskYamlKey.substring(0, taskYamlKey.length() - ".task.yaml".length());
        pulled.remove(taskYamlKey);
        // Also remove companion script file if present
        pulled.keySet().removeIf(k -> k.startsWith(base + ".") && !k.endsWith(".yaml"));

        ProjectSyncDtos.PushCommand cmd = new ProjectSyncDtos.PushCommand(
                pulled, src.baseline(), false, null, null);
        ProjectSyncDtos.DiffPreview diff = syncService.diff(newPid, 1L, cmd);

        assertThat(diff.removed()).as("should report exactly one removed task").hasSize(1);
        assertThat(diff.removed().get(0).entityType()).isEqualTo("TASK");
    }

    /** US3 / SC-002: pull→push (no change)→re-pull to clean project → entity set equivalent.
     *  Verifies identity stability across the full round-trip. */
    @Test
    void roundtripIsStable_noFalseRemoval() {
        // 1. Pull project 1
        ProjectSyncDtos.PullResult src = syncService.pull(1L, 1L);
        long srcTaskCount = src.bundle().files().keySet().stream()
                .filter(k -> k.endsWith(".task.yaml")).count();
        List<String> srcTaskSlugs = src.bundle().files().keySet().stream()
                .filter(k -> k.endsWith(".task.yaml")).sorted().toList();

        // 2. Create empty project and push
        Project p = new Project();
        p.setTenantId(1L); p.setCode("rt-stable"); p.setName("roundtrip stable");
        p.setStatus("ACTIVE"); p.setDeleted(0); p.setVersion(0);
        p.setCreatedAt(LocalDateTime.now()); p.setUpdatedAt(LocalDateTime.now());
        Long newPid = projectRepo.save(p).getId();

        // Clone datasources for the new project (needed for tasks with ds references)
        for (Datasource srcDs : datasourceRepo.findByProjectId(1L)) {
            Datasource d = new Datasource();
            d.setTenantId(1L); d.setProjectId(newPid);
            d.setName(srcDs.getName()); d.setTypeCode(srcDs.getTypeCode());
            d.setDeleted(0); d.setVersion(0);
            d.setCreatedAt(LocalDateTime.now()); d.setUpdatedAt(LocalDateTime.now());
            datasourceRepo.save(d);
        }

        // 3. Push to new project
        ProjectSyncDtos.PushCommand pushCmd = new ProjectSyncDtos.PushCommand(
                src.bundle().files(), null, false, src.fileCount(), "roundtrip test");
        ProjectSyncDtos.PushResult pushRes = syncService.push(newPid, 1L, 1L, pushCmd);

        // 4. Pull the new project
        ProjectSyncDtos.PullResult roundtrip = syncService.pull(newPid, 1L);
        long rtTaskCount = roundtrip.bundle().files().keySet().stream()
                .filter(k -> k.endsWith(".task.yaml")).count();

        // 5. Entity count must match
        assertThat(rtTaskCount).as("roundtrip must preserve entity count").isEqualTo(srcTaskCount);

        // 6. No "-" collapsed slugs in the roundtrip result
        assertThat(roundtrip.bundle().files().keySet())
                .as("roundtrip must not produce collapsed slugs")
                .noneMatch(k -> k.endsWith("/-.task.yaml") || k.equals("-.task.yaml"));

        // 7. Push had zero removals on the new project (all insert)
        assertThat(pushRes.deleted().task()).as("no tasks should be falsely removed").isEqualTo(0);
    }
}
