package com.dataweave.api;

import com.dataweave.master.application.WorkflowService;
import com.dataweave.master.application.WorkflowService.DriftResult;
import com.dataweave.master.application.WorkflowTriggerService;
import com.dataweave.master.domain.TaskInstance;
import com.dataweave.master.domain.TaskInstanceRepository;
import com.dataweave.master.domain.WorkflowDef;
import com.dataweave.master.domain.WorkflowDefRepository;
import com.dataweave.master.domain.WorkflowInstance;
import com.dataweave.master.domain.WorkflowInstanceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * workflow-version-binding 服务级（H2 独立内存库 + <strong>自带种子</strong>，不依赖 data.sql——
 * 免疫并发对 data.sql 的重写）：
 * <ul>
 *   <li>触发器以已发布快照为唯一真相物化——拓扑+各节点 task 版本钉死，{@code workflow_version_no} 名副其实；</li>
 *   <li>任务发新版（未重新晋级）→ 工作流漂移；触发仍跑快照钉死的旧版；</li>
 *   <li>重新晋级（复用 publish，含漂移判定放行）重建快照、漂移消除、触发改跑新版；</li>
 *   <li>无快照（version=0）回退 live 物化；快照节点 live 已删则跳过；</li>
 *   <li>{@code env} 落值：cron/正式手动 PROD、试跑 DEV。</li>
 * </ul>
 * 自建夹具：workflow 9000（ONLINE v1，节点 n1/n2 绑 task 9001/9002，快照全钉 v1，边 n1→n2）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("h2")
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:wvbtest;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE")
class WorkflowVersionBindingTest {

    static final long WF = 9000;
    static final long T1 = 9001;
    static final long T2 = 9002;
    static final long N1 = 9001;
    static final long N2 = 9002;
    static final String SNAPSHOT_V1 = "{\"nodes\":["
            + "{\"nodeKey\":\"n1\",\"nodeType\":\"TASK\",\"taskId\":9001,\"taskVersionNo\":1,\"name\":\"N1\",\"posX\":0,\"posY\":0},"
            + "{\"nodeKey\":\"n2\",\"nodeType\":\"TASK\",\"taskId\":9002,\"taskVersionNo\":1,\"name\":\"N2\",\"posX\":100,\"posY\":0}"
            + "],\"edges\":[{\"fromNodeKey\":\"n1\",\"toNodeKey\":\"n2\"}]}";

    @Autowired WorkflowService workflowService;
    @Autowired WorkflowTriggerService triggerService;
    @Autowired WorkflowDefRepository workflowDefRepository;
    @Autowired WorkflowInstanceRepository workflowInstanceRepository;
    @Autowired TaskInstanceRepository taskInstanceRepository;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        // 清掉自建夹具（隔离库跨测试方法持久）后重建，保证每个测试干净起点。
        jdbc.update("DELETE FROM task_instance WHERE task_id IN (?,?) OR workflow_instance_id IN "
                + "(SELECT id FROM workflow_instance WHERE workflow_id=?)", T1, T2, WF);
        jdbc.update("DELETE FROM workflow_instance WHERE workflow_id=?", WF);
        jdbc.update("DELETE FROM workflow_edge WHERE workflow_id=?", WF);
        jdbc.update("DELETE FROM workflow_node WHERE workflow_id=?", WF);
        jdbc.update("DELETE FROM workflow_def_version WHERE workflow_id=?", WF);
        jdbc.update("DELETE FROM workflow_def WHERE id=?", WF);
        jdbc.update("DELETE FROM task_def WHERE id IN (?,?)", T1, T2);

        jdbc.update("INSERT INTO task_def (id, tenant_id, project_id, name, type, content, status, "
                + "current_version_no, has_draft_change, deleted, version) VALUES "
                + "(?,1,1,'T1','SHELL','echo 1','ONLINE',1,0,0,0)", T1);
        jdbc.update("INSERT INTO task_def (id, tenant_id, project_id, name, type, content, status, "
                + "current_version_no, has_draft_change, deleted, version) VALUES "
                + "(?,1,1,'T2','SHELL','echo 2','ONLINE',1,0,0,0)", T2);
        jdbc.update("INSERT INTO workflow_def (id, tenant_id, project_id, name, status, "
                + "current_version_no, has_draft_change, deleted, version) VALUES "
                + "(?,1,1,'WVB流','ONLINE',1,0,0,0)", WF);
        jdbc.update("INSERT INTO workflow_def_version (id, tenant_id, project_id, workflow_id, version_no, "
                + "name, dag_snapshot_json, created_at) VALUES (?,1,1,?,1,'WVB流',?, CURRENT_TIMESTAMP)",
                WF, WF, SNAPSHOT_V1);
        jdbc.update("INSERT INTO workflow_node (id, tenant_id, project_id, workflow_id, task_id, node_type, "
                + "node_key, name, pos_x, pos_y, deleted, version) VALUES (?,1,1,?,?,'TASK','n1','N1',0,0,0,0)", N1, WF, T1);
        jdbc.update("INSERT INTO workflow_node (id, tenant_id, project_id, workflow_id, task_id, node_type, "
                + "node_key, name, pos_x, pos_y, deleted, version) VALUES (?,1,1,?,?,'TASK','n2','N2',100,0,0,0)", N2, WF, T2);
        jdbc.update("INSERT INTO workflow_edge (id, tenant_id, project_id, workflow_id, from_node_id, "
                + "to_node_id, deleted, version) VALUES (?,1,1,?,?,?,0,0)", 9001L, WF, N1, N2);
    }

    private WorkflowDef wf() {
        return workflowDefRepository.findById(WF).orElseThrow();
    }

    private Integer pinnedVersion(UUID wiId, long taskId) {
        return jdbc.queryForObject(
                "SELECT task_version_no FROM task_instance WHERE workflow_instance_id=? AND task_id=?",
                Integer.class, wiId, taskId);
    }

    @Test
    void trigger_materializesFromSnapshot_pinsVersion_versionNoFaithful_envProd() {
        // 任务 T1 在工作流外发了新版（current_version_no 1→5），但工作流未重新晋级
        jdbc.update("UPDATE task_def SET current_version_no=5 WHERE id=?", T1);

        UUID wiId = triggerService.trigger(wf(), "MANUAL", "2026-06-20", null, Locale.CHINA);

        WorkflowInstance wi = workflowInstanceRepository.findById(wiId).orElseThrow();
        assertThat(wi.getWorkflowVersionNo()).isEqualTo(1);   // 名副其实：跑的是快照 v1
        assertThat(wi.getEnv()).isEqualTo("PROD");
        assertThat(wi.getTotalTasks()).isEqualTo(2);

        assertThat(pinnedVersion(wiId, T1)).isEqualTo(1);     // 快照钉死 v1，非 live v5
        List<TaskInstance> tis = taskInstanceRepository.findByWorkflowInstanceId(wiId);
        assertThat(tis).hasSize(2).allSatisfy(ti -> assertThat(ti.getEnv()).isEqualTo("PROD"));
    }

    @Test
    void computeDrift_taskNewVersion_flagsDriftWithDetail() {
        jdbc.update("UPDATE task_def SET current_version_no=5 WHERE id=?", T1);

        DriftResult d = workflowService.computeDrift(WF);

        assertThat(d.drifted()).isTrue();
        assertThat(d.dagDraft()).isFalse();
        assertThat(d.driftedNodes()).anySatisfy(n -> {
            assertThat(n.nodeKey()).isEqualTo("n1");
            assertThat(n.pinned()).isEqualTo(1);
            assertThat(n.latest()).isEqualTo(5);
        });
    }

    @Test
    void computeDrift_allCurrent_notDrifted() {
        DriftResult d = workflowService.computeDrift(WF);

        assertThat(d.drifted()).isFalse();
        assertThat(d.dagDraft()).isFalse();
        assertThat(d.driftedNodes()).isEmpty();
    }

    @Test
    void computeDrift_dagDraftChange_flagsDrift() {
        jdbc.update("UPDATE workflow_def SET has_draft_change=1 WHERE id=?", WF);

        DriftResult d = workflowService.computeDrift(WF);

        assertThat(d.drifted()).isTrue();
        assertThat(d.dagDraft()).isTrue();
    }

    @Test
    void rePromote_rebuildsSnapshot_clearsDrift_triggerUsesNewVersion() {
        jdbc.update("UPDATE task_def SET current_version_no=5 WHERE id=?", T1);
        assertThat(workflowService.computeDrift(WF).drifted()).isTrue();

        // 重新晋级 = 复用 publish；漂移判定放行（hasDraftChange=0 但 drifted）
        workflowService.publish(WF, "重新晋级到最新");

        assertThat(workflowService.computeDrift(WF).drifted()).isFalse();
        assertThat(wf().getCurrentVersionNo()).isEqualTo(2);

        UUID wiId = triggerService.trigger(wf(), "MANUAL", "2026-06-20", null, Locale.CHINA);
        assertThat(pinnedVersion(wiId, T1)).isEqualTo(5);
        assertThat(workflowInstanceRepository.findById(wiId).orElseThrow()
                .getWorkflowVersionNo()).isEqualTo(2);
    }

    @Test
    void trigger_noSnapshot_fallsBackToLiveVersion() {
        jdbc.update("UPDATE task_def SET current_version_no=7 WHERE id=?", T1);
        jdbc.update("UPDATE workflow_def SET current_version_no=0 WHERE id=?", WF);

        UUID wiId = triggerService.trigger(wf(), "MANUAL", "2026-06-20", null, Locale.CHINA);

        assertThat(pinnedVersion(wiId, T1)).isEqualTo(7);   // 回退 live，非快照
    }

    @Test
    void trigger_snapshotNodeMissingInLive_skipsAndContinues() {
        // 软删 live 节点 n2，快照仍含 n2 → 触发跳过 n2，不静默丢全图
        jdbc.update("UPDATE workflow_node SET deleted=1 WHERE id=?", N2);

        UUID wiId = triggerService.trigger(wf(), "MANUAL", "2026-06-20", null, Locale.CHINA);

        assertThat(workflowInstanceRepository.findById(wiId).orElseThrow().getTotalTasks()).isEqualTo(1);
        Integer n2count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_instance WHERE workflow_instance_id=? AND task_id=?",
                Integer.class, wiId, T2);
        assertThat(n2count).isZero();
    }

    @Test
    void testRun_envDev_manualTaskRun_envProd() {
        UUID testId = triggerService.triggerTestRun(T1, "2026-06-20", Locale.CHINA);
        assertThat(jdbc.queryForObject("SELECT env FROM task_instance WHERE id=?", String.class, testId))
                .isEqualTo("DEV");

        UUID manualId = triggerService.triggerManualTaskRun(T1, "2026-06-20", Locale.CHINA);
        assertThat(jdbc.queryForObject("SELECT env FROM task_instance WHERE id=?", String.class, manualId))
                .isEqualTo("PROD");
    }
}
