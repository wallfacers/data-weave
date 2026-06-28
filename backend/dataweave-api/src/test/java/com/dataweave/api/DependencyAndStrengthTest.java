package com.dataweave.api;

import com.dataweave.master.application.WorkflowService;
import com.dataweave.master.application.WorkflowService.DagEdgeDto;
import com.dataweave.master.application.WorkflowService.DagPayload;
import com.dataweave.master.application.WorkflowService.DagView;
import com.dataweave.master.application.WorkflowService.DependencyDto;
import com.dataweave.master.i18n.BizException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 跨周期依赖 CRUD + 边 strength 读写单测（5.5）：独立 H2 库隔离（不与 KernelSchedulingTest 的 kernelsched 共享）。
 * 覆盖：①saveDag 透传/回读 edge strength（WEAK/STRONG）；②createDependency 自依赖按 nodeKey 解析 +
 * earliestBizDate 持久化 + list/delete；③跨流依赖成环拒绝（wf1→wf3 后 wf3→wf1 抛 BizException）。
 */
@SpringBootTest
@ActiveProfiles("h2")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:depstrength;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
})
class DependencyAndStrengthTest {

    @Autowired
    WorkflowService workflowService;

    @Test
    void saveDag_persistsEdgeStrength_andReadsBack() {
        DagView dag = workflowService.readDag(3L);
        DagEdgeDto first = dag.edges().get(0);
        // 把第一条边改为 WEAK，其余保持 STRONG
        List<DagEdgeDto> edges = dag.edges().stream()
                .map(e -> new DagEdgeDto(e.fromNodeKey(), e.toNodeKey(),
                        e.fromNodeKey().equals(first.fromNodeKey()) && e.toNodeKey().equals(first.toNodeKey())
                                ? "WEAK" : "STRONG"))
                .toList();
        workflowService.saveDag(3L, new DagPayload(dag.version(), dag.nodes(), edges));

        DagView reread = workflowService.readDag(3L);
        String changed = reread.edges().stream()
                .filter(e -> e.fromNodeKey().equals(first.fromNodeKey()) && e.toNodeKey().equals(first.toNodeKey()))
                .map(DagEdgeDto::strength).findFirst().orElse("?");
        assertThat(changed).isEqualTo("WEAK");
        assertThat(reread.edges().stream().filter(e -> !"WEAK".equals(e.strength())).count()).isGreaterThan(0);
    }

    @Test
    void createDependency_selfDependence_resolvesNodeKey_andCrud() {
        // 自依赖：wf3.n1 依赖自身上一周期（dependWorkflowId 同流、dependNodeKey 同 n1），earliest 首周期豁免
        DependencyDto saved = workflowService.createDependency(3L,
                new DependencyDto(null, null, 3L, null, "n1", "n1", "LAST_DAY", "2026-06-10", 1));
        assertThat(saved.nodeKey()).isEqualTo("n1");
        assertThat(saved.dependNodeKey()).isEqualTo("n1");
        assertThat(saved.dateOffset()).isEqualTo("LAST_DAY");
        assertThat(saved.earliestBizDate()).isEqualTo("2026-06-10");

        List<DependencyDto> list = workflowService.listDependencies(3L);
        assertThat(list).extracting(DependencyDto::nodeKey).contains("n1");

        assertThat(saved.id()).isNotNull();
        workflowService.deleteDependency(3L, saved.id());
        assertThat(workflowService.listDependencies(3L).stream().map(DependencyDto::nodeKey))
                .doesNotContain("n1");
    }

    @Test
    void createDependency_crossCycleRejected() {
        // wf1 → wf3（无环，合法）：wf1.n3 依赖 wf3.n1（seed 重构后 wf1 仅存节点 n3，n1/n2 已移除）
        workflowService.createDependency(1L,
                new DependencyDto(null, null, 3L, null, "n3", "n1", "LAST_DAY", null, 1));
        // wf3 → wf1（与既有 wf1→wf3 成环）→ 抛 BizException
        assertThatThrownBy(() -> workflowService.createDependency(3L,
                new DependencyDto(null, null, 1L, null, "n1", "n3", "LAST_DAY", null, 1)))
                .isInstanceOf(BizException.class);
    }
}
