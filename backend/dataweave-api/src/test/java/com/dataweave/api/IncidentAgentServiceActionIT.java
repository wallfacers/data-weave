package com.dataweave.api;

import java.time.LocalDateTime;
import java.util.UUID;

import com.dataweave.master.application.TaskService;
import com.dataweave.master.application.incident.IncidentAgentService;
import com.dataweave.master.domain.TaskDef;
import com.dataweave.master.domain.TaskDefRepository;
import com.dataweave.master.domain.incident.IncidentClassifications;
import com.dataweave.master.domain.incident.IncidentStates;
import com.dataweave.master.domain.incident.ProposalStatuses;
import com.dataweave.master.infrastructure.incident.IncidentProposalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 069 T023/T024：{@code IncidentAgentService.actOrVerify} 的确定性验证收口分支——
 * 防循环上限、已发布提案验证成功收口、验证失败回滚基线版本+转人工（绝不生成第二份提案）。
 * 全部分支零 LLM 依赖，真 Spring 上下文复用全部真实协作者（TaskService/仓储），非桩件假绿。
 */
@SpringBootTest
@ActiveProfiles("h2")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:dataweave-incident-action-069;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
})
@DisplayName("069 T023/T024 actOrVerify 验证收口分支（提案回滚/防循环）")
class IncidentAgentServiceActionIT {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    IncidentAgentService agentService;
    @Autowired
    IncidentProposalRepository proposalRepo;
    @Autowired
    TaskDefRepository taskDefRepository;
    @Autowired
    TaskService taskService;

    private long taskId;
    private UUID instanceId;

    @BeforeEach
    void setUp() {
        taskId = insertTaskDef("t-action-" + UUID.randomUUID());
        instanceId = insertInstance(taskId, "FAILED");
    }

    private long insertTaskDef(String name) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO task_def (tenant_id, project_id, name, type, content, status, "
                        + "current_version_no, created_at, updated_at, deleted, version) "
                        + "VALUES (1,1,?,'SQL','select 1','ONLINE',0,?,?,0,0)",
                name, now, now);
        return jdbc.queryForObject("SELECT id FROM task_def WHERE name=?", Long.class, name);
    }

    private UUID insertInstance(long taskId, String state) {
        UUID id = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO task_instance (id, tenant_id, project_id, task_id, task_def_name, run_mode, "
                        + "state, attempt, created_at, updated_at, deleted, version) "
                        + "VALUES (?,1,1,?,'t-action','NORMAL',?,1,?,?,0,0)",
                id, taskId, state, now, now);
        return id;
    }

    private void setInstanceState(UUID id, String state) {
        jdbc.update("UPDATE task_instance SET state=?, updated_at=? WHERE id=?", state, LocalDateTime.now(), id);
    }

    private UUID openActingIncident(String classification, int autoActionCount, UUID latestInstanceId) {
        return openIncident(classification, autoActionCount, latestInstanceId, "ACTING");
    }

    private UUID openIncident(String classification, int autoActionCount, UUID latestInstanceId, String state) {
        UUID id = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO incident (id, tenant_id, project_id, task_def_id, task_def_name, "
                        + "first_instance_id, latest_instance_id, instance_count, trigger_source, "
                        + "classification, confidence, state, open_key, auto_action_count, opened_at, "
                        + "version, created_at, updated_at) "
                        + "VALUES (?,1,1,?,'t-action',?,?,1,'MANUAL',?,'HIGH',?,?,?,?,0,?,?)",
                id, taskId, latestInstanceId, latestInstanceId, classification, state, taskId, autoActionCount,
                now, now, now);
        return id;
    }

    @Test
    @DisplayName("防循环：auto_action_count 达上限（默认 3）直接转 NEEDS_HUMAN，计数不再自增")
    void actOrVerify_autoActionCapReached_escalatesWithoutNewAction() {
        UUID incidentId = openActingIncident(IncidentClassifications.TRANSIENT, 3, instanceId);
        setInstanceState(instanceId, "FAILED");

        agentService.actOrVerify(incidentId);

        String state = jdbc.queryForObject("SELECT state FROM incident WHERE id=?", String.class, incidentId);
        assertThat(state).isEqualTo(IncidentStates.NEEDS_HUMAN);
        Integer count = jdbc.queryForObject("SELECT auto_action_count FROM incident WHERE id=?", Integer.class, incidentId);
        assertThat(count).isEqualTo(3);
    }

    @Test
    @DisplayName("已发布提案验证成功：VERIFIED + 事故自动收口")
    void actOrVerify_publishedProposalSucceeds_verifiesAndCloses() {
        TaskDef task = taskDefRepository.findById(taskId).orElseThrow();
        taskService.writeTaskVersionSnapshot(task, null, "base");
        UUID incidentId = openActingIncident(IncidentClassifications.CODE, 1, instanceId);
        UUID proposalId = proposalRepo.insert(incidentId, taskId, 1, "select 2 -- fixed", "fix", "{}");
        proposalRepo.casStatus(proposalId, ProposalStatuses.PENDING, ProposalStatuses.PUBLISHED);
        setInstanceState(instanceId, "SUCCESS");

        agentService.actOrVerify(incidentId);

        String incState = jdbc.queryForObject("SELECT state FROM incident WHERE id=?", String.class, incidentId);
        assertThat(incState).isEqualTo(IncidentStates.RESOLVED);
        String propStatus = jdbc.queryForObject("SELECT status FROM incident_proposal WHERE id=?", String.class, proposalId);
        assertThat(propStatus).isEqualTo(ProposalStatuses.VERIFIED);
    }

    @Test
    @DisplayName("已发布提案验证失败：VERIFY_FAILED + 回滚至基线版本 + 转人工，不生成第二份提案")
    void actOrVerify_publishedProposalFails_rollsBackAndEscalates() {
        TaskDef task = taskDefRepository.findById(taskId).orElseThrow();
        taskService.writeTaskVersionSnapshot(task, null, "base"); // v1，基线内容 = "select 1"
        task.setContent("select 2 -- fixed"); // 模拟发布时已把内容改写为提案内容
        taskDefRepository.save(task);

        UUID incidentId = openActingIncident(IncidentClassifications.CODE, 1, instanceId);
        UUID proposalId = proposalRepo.insert(incidentId, taskId, 1, "select 2 -- fixed", "fix", "{}");
        proposalRepo.casStatus(proposalId, ProposalStatuses.PENDING, ProposalStatuses.PUBLISHED);
        setInstanceState(instanceId, "FAILED");

        agentService.actOrVerify(incidentId);

        String incState = jdbc.queryForObject("SELECT state FROM incident WHERE id=?", String.class, incidentId);
        assertThat(incState).isEqualTo(IncidentStates.NEEDS_HUMAN);
        String propStatus = jdbc.queryForObject("SELECT status FROM incident_proposal WHERE id=?", String.class, proposalId);
        assertThat(propStatus).isEqualTo(ProposalStatuses.ROLLED_BACK);

        TaskDef rolledBack = taskDefRepository.findById(taskId).orElseThrow();
        assertThat(rolledBack.getContent()).isEqualTo("select 1"); // 回滚至基线内容
        assertThat(rolledBack.getCurrentVersionNo()).isEqualTo(2); // 回滚也是新快照，版本只进不退

        Integer proposalCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_proposal WHERE incident_id=?", Integer.class, incidentId);
        assertThat(proposalCount).isEqualTo(1); // 未生成第二份提案（防循环）
    }

    // ---- 069 T025/T027: 不可自愈分型零徒劳重试 ----

    @Test
    @DisplayName("CONFIG_CREDENTIAL：零自动重跑，直接转 NEEDS_HUMAN，实例状态不变")
    void actOrVerify_configCredential_escalatesWithZeroRerun() {
        UUID incidentId = openActingIncident(IncidentClassifications.CONFIG_CREDENTIAL, 0, instanceId);

        agentService.actOrVerify(incidentId);

        assertThat(jdbc.queryForObject("SELECT state FROM incident WHERE id=?", String.class, incidentId))
                .isEqualTo(IncidentStates.NEEDS_HUMAN);
        assertThat(jdbc.queryForObject("SELECT state FROM task_instance WHERE id=?", String.class, instanceId))
                .isEqualTo("FAILED"); // 从未被重置为 WAITING（零 rerun）
        Integer actionCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_action WHERE target_id=?", Integer.class, instanceId.toString());
        assertThat(actionCount).isZero();
    }

    @Test
    @DisplayName("UPSTREAM_DATA：零自动重跑，直接转 NEEDS_HUMAN，实例状态不变")
    void actOrVerify_upstreamData_escalatesWithZeroRerun() {
        UUID incidentId = openActingIncident(IncidentClassifications.UPSTREAM_DATA, 0, instanceId);

        agentService.actOrVerify(incidentId);

        assertThat(jdbc.queryForObject("SELECT state FROM incident WHERE id=?", String.class, incidentId))
                .isEqualTo(IncidentStates.NEEDS_HUMAN);
        assertThat(jdbc.queryForObject("SELECT state FROM task_instance WHERE id=?", String.class, instanceId))
                .isEqualTo("FAILED");
    }

    // ---- 069 T026/T027: 人工协同 ----

    @Test
    @DisplayName("mark-handled 后复验成功：事故收口 close_kind=HUMAN_ASSISTED")
    void markHandled_thenSuccess_closesHumanAssisted() {
        UUID incidentId = openIncident(IncidentClassifications.CONFIG_CREDENTIAL, 1, instanceId, "NEEDS_HUMAN");

        agentService.markHandled(incidentId, "已修正数据源密码", "alice");

        // 复验已触发：实例被重置为 WAITING（rerunInstance 语义），事故转 ACTING
        assertThat(jdbc.queryForObject("SELECT state FROM task_instance WHERE id=?", String.class, instanceId))
                .isEqualTo("WAITING");
        assertThat(jdbc.queryForObject("SELECT state FROM incident WHERE id=?", String.class, incidentId))
                .isEqualTo(IncidentStates.ACTING);

        // 模拟重跑真实完成：SUCCESS，下一轮 sweep 观测收口
        setInstanceState(instanceId, "SUCCESS");
        agentService.actOrVerify(incidentId);

        assertThat(jdbc.queryForObject("SELECT state FROM incident WHERE id=?", String.class, incidentId))
                .isEqualTo(IncidentStates.RESOLVED);
        assertThat(jdbc.queryForObject("SELECT close_kind FROM incident WHERE id=?", String.class, incidentId))
                .isEqualTo("HUMAN_ASSISTED");
        // HUMAN_SAY 备注已落库
        Integer humanSayCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_message WHERE incident_id=? AND kind='HUMAN_SAY'",
                Integer.class, incidentId);
        assertThat(humanSayCount).isEqualTo(1);
    }

    @Test
    @DisplayName("mark-handled 后复验仍失败：重新转 NEEDS_HUMAN，收口提示位不残留")
    void markHandled_thenFailAgain_reEscalatesAndClearsHint() {
        UUID incidentId = openIncident(IncidentClassifications.CONFIG_CREDENTIAL, 1, instanceId, "NEEDS_HUMAN");

        agentService.markHandled(incidentId, "以为修好了", "alice");
        setInstanceState(instanceId, "FAILED"); // 复验仍失败（密码依然不对）
        agentService.actOrVerify(incidentId);

        assertThat(jdbc.queryForObject("SELECT state FROM incident WHERE id=?", String.class, incidentId))
                .isEqualTo(IncidentStates.NEEDS_HUMAN);
        // 收口提示位必须清空，防止未来某次不相关的 AUTO 成功被误标 HUMAN_ASSISTED
        String closeKind = jdbc.queryForObject("SELECT close_kind FROM incident WHERE id=?", String.class, incidentId);
        assertThat(closeKind).isNull();
    }

    @Test
    @DisplayName("mark-handled 状态前置校验：非 NEEDS_HUMAN 事故拒绝")
    void markHandled_wrongState_throws() {
        UUID incidentId = openActingIncident(IncidentClassifications.CONFIG_CREDENTIAL, 0, instanceId);
        assertThatThrownBy(() -> agentService.markHandled(incidentId, "note", "alice"))
                .isInstanceOf(com.dataweave.master.i18n.BizException.class);
    }

    @Test
    @DisplayName("人工直接收口：任意非终态 → RESOLVED(MANUAL)，落审计消息含 reason+actor")
    void closeManual_recordsAuditMessage() {
        UUID incidentId = openActingIncident(IncidentClassifications.TRANSIENT, 0, instanceId);

        agentService.closeManual(incidentId, "确认无需处理，直接关闭", "bob");

        assertThat(jdbc.queryForObject("SELECT state FROM incident WHERE id=?", String.class, incidentId))
                .isEqualTo(IncidentStates.RESOLVED);
        assertThat(jdbc.queryForObject("SELECT close_kind FROM incident WHERE id=?", String.class, incidentId))
                .isEqualTo("MANUAL");
        String content = jdbc.queryForObject(
                "SELECT content FROM incident_message WHERE incident_id=? AND kind='SYSTEM' AND actor='bob'",
                String.class, incidentId);
        assertThat(content).contains("确认无需处理，直接关闭");
    }

    @Test
    @DisplayName("人工直接收口：reason 必填，空白拒绝")
    void closeManual_blankReason_throws() {
        UUID incidentId = openActingIncident(IncidentClassifications.TRANSIENT, 0, instanceId);
        assertThatThrownBy(() -> agentService.closeManual(incidentId, "  ", "bob"))
                .isInstanceOf(com.dataweave.master.i18n.BizException.class);
    }
}
