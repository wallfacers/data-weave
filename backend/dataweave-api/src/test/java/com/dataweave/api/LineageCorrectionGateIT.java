package com.dataweave.api;

import java.util.Map;

import com.dataweave.api.infrastructure.TenantContext;
import com.dataweave.master.application.ActionRequest;
import com.dataweave.master.application.GateResult;
import com.dataweave.master.application.GatedActionService;
import com.dataweave.master.application.lineage.LineageCorrectionService;
import com.dataweave.master.domain.AgentAction;
import com.dataweave.master.domain.AgentActionRepository;
import com.dataweave.master.domain.lineage.LineageStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 041 T019：门禁闭环——LINEAGE_EDGE_* 经 GatedActionService L1 直通 → correction 落库 +
 * agent_action 留痕（h2 真库 + 真 PolicyEngine seed，mock 图存储）。
 */
@SpringBootTest
@ActiveProfiles("h2")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class LineageCorrectionGateIT {

    @Autowired
    GatedActionService gatedActionService;

    @Autowired
    LineageCorrectionService correctionService;

    @Autowired
    AgentActionRepository agentActionRepository;

    @Autowired
    tools.jackson.databind.ObjectMapper objectMapper;

    @MockitoBean
    LineageStore lineageStore;

    private static final String TK = "1|datasource:|dw.gate_test";

    @BeforeEach
    void setUp() {
        TenantContext.set(1L, 1L, "admin");
    }

    private GateResult submit(String actionType, long taskDefId) {
        String command = objectMapper.writeValueAsString(Map.of(
                "tenantId", 1L, "projectId", 1L, "taskDefId", taskDefId,
                "direction", "WRITE", "tableKey", TK, "columnKey", ""));
        ActionRequest req = ActionRequest.builder()
                .toolName(actionType).actionType(actionType)
                .targetType("LINEAGE_EDGE").targetId(taskDefId + "|WRITE|" + TK)
                .command(command)
                .actor("1").actorSource("UI")
                .summary("test " + actionType)
                .build();
        return gatedActionService.submit(req);
    }

    @Test
    void removeGoesThroughGateL1AndLeavesAuditTrail() {
        GateResult gr = submit("LINEAGE_EDGE_REMOVE", 601L);
        assertThat(gr.executed()).as("L1 直通执行：%s", gr.message()).isTrue();
        assertThat(gr.level()).isEqualTo("L1");

        // correction 落库
        assertThat(correctionService.listForTask(1, 1, 601))
                .singleElement()
                .satisfies(c -> assertThat(c.status()).isEqualTo("REMOVED"));

        // agent_action 留痕
        AgentAction action = agentActionRepository.findById(gr.actionId()).orElseThrow();
        assertThat(action.getActionType()).isEqualTo("LINEAGE_EDGE_REMOVE");
        assertThat(action.getPolicyLevel()).isEqualTo("L1");
        assertThat(action.getExecutedAt()).isNotNull();
    }

    @Test
    void confirmThenRevokeLifecycle() {
        assertThat(submit("LINEAGE_EDGE_CONFIRM", 602L).executed()).isTrue();
        assertThat(correctionService.listForTask(1, 1, 602))
                .singleElement()
                .satisfies(c -> assertThat(c.status()).isEqualTo("CONFIRMED"));

        assertThat(submit("LINEAGE_CORRECTION_REVOKE", 602L).executed()).isTrue();
        assertThat(correctionService.listForTask(1, 1, 602)).isEmpty();
    }
}
