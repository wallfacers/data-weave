package com.dataweave.master.application;

import com.dataweave.master.domain.TaskDef;
import com.dataweave.master.domain.TaskDefRepository;
import com.dataweave.master.domain.TaskDefVersion;
import com.dataweave.master.domain.TaskDefVersionRepository;
import com.dataweave.master.domain.TaskInstance;
import com.dataweave.master.domain.TaskInstanceRepository;
import com.dataweave.master.domain.WorkerNode;
import com.dataweave.master.domain.WorkflowNodeRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 建任务即建血缘（table-lineage）的 A×B 交叉校验单测：用真实 {@link SqlTableExtractor} 解析 SQL，
 * 捕获传给 {@link LineageGraphService#recordDesignTimeIo} 的边，断言来源与可信度。
 */
class TaskServiceLineageTest {

    private final TaskDefRepository taskDefRepo = mock(TaskDefRepository.class);
    private final TaskDefVersionRepository verRepo = mock(TaskDefVersionRepository.class);
    private final TaskInstanceRepository instRepo = mock(TaskInstanceRepository.class);
    private final WorkflowNodeRepository workflowNodeRepo = mock(WorkflowNodeRepository.class);
    private final FleetService fleetService = mock(FleetService.class);
    private final LineageGraphService lineage = mock(LineageGraphService.class);

    private TaskService newService() {
        when(taskDefRepo.save(any())).thenAnswer(inv -> {
            TaskDef t = inv.getArgument(0);
            t.setId(100L);
            return t;
        });
        when(verRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(instRepo.save(any())).thenAnswer(inv -> {
            TaskInstance i = inv.getArgument(0);
            i.setId(UUID.randomUUID());
            return i;
        });
        when(fleetService.pickLeastLoadedOnline()).thenReturn(Optional.<WorkerNode>empty());
        return new TaskService(taskDefRepo, verRepo, instRepo, workflowNodeRepo, fleetService, null,
                lineage, new SqlTableExtractor());
    }

    @SuppressWarnings("unchecked")
    private List<LineageGraphService.EdgeInput> capture() {
        ArgumentCaptor<List<LineageGraphService.EdgeInput>> cap = ArgumentCaptor.forClass(List.class);
        verify(lineage).recordDesignTimeIo(anyLong(), anyLong(), anyLong(), anyInt(), cap.capture());
        return cap.getValue();
    }

    private LineageGraphService.EdgeInput edge(List<LineageGraphService.EdgeInput> es, String dir, String table) {
        return es.stream()
                .filter(e -> e.direction().equals(dir) && e.qualifiedName().equalsIgnoreCase(table))
                .findFirst().orElseThrow(() -> new AssertionError("no " + dir + " edge for " + table));
    }

    @Test
    void agent_declaration_and_sql_parse_agree_confirmed() {
        TaskService svc = newService();
        svc.createAndOnline("t", "SQL", "INSERT INTO dwd_order SELECT * FROM ods_order", "0 0 8 * * ?",
                1L, 2L, List.of("ods_order"), List.of("dwd_order"));
        var edges = capture();
        var w = edge(edges, "WRITE", "dwd_order");
        assertThat(w.source()).isEqualTo("AGENT");
        assertThat(w.confidence()).isEqualTo("CONFIRMED");
        var r = edge(edges, "READ", "ods_order");
        assertThat(r.source()).isEqualTo("AGENT");
        assertThat(r.confidence()).isEqualTo("CONFIRMED");
    }

    @Test
    void agent_declares_write_sql_does_not_conflict() {
        TaskService svc = newService();
        // Agent 声明写 dwd_order，但 SQL 只读不写
        svc.createAndOnline("t", "SQL", "SELECT * FROM ods_order", "0 0 8 * * ?",
                1L, 2L, List.of(), List.of("dwd_order"));
        var w = edge(capture(), "WRITE", "dwd_order");
        assertThat(w.source()).isEqualTo("AGENT");
        assertThat(w.confidence()).isEqualTo("CONFLICT");
    }

    @Test
    void sql_parse_only_no_agent_declaration_confirmed() {
        TaskService svc = newService();
        svc.createAndOnline("t", "SQL", "INSERT INTO dwd_order SELECT * FROM ods_order", "0 0 8 * * ?");
        var edges = capture();
        assertThat(edge(edges, "WRITE", "dwd_order").source()).isEqualTo("SQL_PARSED");
        assertThat(edge(edges, "WRITE", "dwd_order").confidence()).isEqualTo("CONFIRMED");
        assertThat(edge(edges, "READ", "ods_order").source()).isEqualTo("SQL_PARSED");
    }

    @Test
    void shell_unparseable_agent_declaration_unverified() {
        TaskService svc = newService();
        svc.createAndOnline("t", "SHELL", "spark-submit job.py", "0 0 8 * * ?",
                null, null, List.of("ods_log"), List.of("dwd_log"));
        var edges = capture();
        assertThat(edge(edges, "READ", "ods_log").confidence()).isEqualTo("UNVERIFIED");
        assertThat(edge(edges, "WRITE", "dwd_log").confidence()).isEqualTo("UNVERIFIED");
        assertThat(edge(edges, "WRITE", "dwd_log").source()).isEqualTo("AGENT");
    }

    @Test
    void no_io_and_unparseable_records_nothing() {
        TaskService svc = newService();
        svc.createAndOnline("t", "SHELL", "echo hi", "0 0 8 * * ?");
        verify(lineage, never()).recordDesignTimeIo(anyLong(), anyLong(), anyLong(), anyInt(), any());
    }
}
