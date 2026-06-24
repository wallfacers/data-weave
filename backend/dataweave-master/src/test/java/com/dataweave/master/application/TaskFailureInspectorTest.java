package com.dataweave.master.application;

import com.dataweave.master.domain.Finding;
import com.dataweave.master.domain.TaskInstance;
import com.dataweave.master.domain.TaskInstanceRepository;
import com.dataweave.master.domain.TaskDiagnosis;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 失败巡检器：未处理 FAILED → 诊断 → 映射 Finding；已处理跳过；suggestions→actions 映射。
 */
@ExtendWith(MockitoExtension.class)
class TaskFailureInspectorTest {

    @Mock
    private TaskInstanceRepository instanceRepository;
    @Mock
    private DiagnosisService diagnosisService;
    @Mock
    private FindingService findingService;

    private TaskFailureInspector inspector;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final UUID INST = UUID.fromString("01910000-0010-7000-8000-000000000001");

    @BeforeEach
    void setUp() {
        inspector = new TaskFailureInspector(instanceRepository, diagnosisService, findingService, objectMapper);
    }

    private TaskInstance failed(UUID id) {
        TaskInstance i = new TaskInstance();
        i.setId(id);
        i.setState("FAILED");
        return i;
    }

    private TaskDiagnosis diagnosis() {
        TaskDiagnosis d = new TaskDiagnosis();
        d.setId(11L);
        d.setTenantId(1L);
        d.setProjectId(1L);
        d.setTitle("订单宽表加工 失败 · OOM");
        d.setRootCause("node-3 内存 95%");
        d.setContextJson("{\"nodeMem\":95,\"concurrentTasks\":2}");
        d.setSuggestionsJson("[{\"action\":\"RERUN_MORE_MEMORY\",\"label\":\"调大内存重跑\"},{\"action\":\"MIGRATE_NODE\",\"label\":\"迁移重跑\"}]");
        return d;
    }

    @Test
    void inspect_producesMappedFinding_forUndiagnosedFailed() {
        when(instanceRepository.findByState("FAILED")).thenReturn(List.of(failed(INST)));
        when(findingService.exists("TASK_FAILURE", "TASK_INSTANCE", INST.toString())).thenReturn(false);
        when(diagnosisService.diagnoseInstance(INST)).thenReturn(diagnosis());

        List<Finding> out = inspector.inspect();

        assertThat(out).hasSize(1);
        Finding f = out.get(0);
        assertThat(f.getSource()).isEqualTo("TASK_FAILURE");
        assertThat(f.getTargetType()).isEqualTo("TASK_INSTANCE");
        assertThat(f.getTargetId()).isEqualTo(INST.toString());
        assertThat(f.getSeverity()).isEqualTo("CRITICAL");
        assertThat(f.getTitle()).contains("OOM");
        assertThat(f.getEvidenceJson()).contains("nodeMem");
        assertThat(f.getTaskDiagnosisId()).isEqualTo(11L);
        // suggestions {action,label} → actions {key,label,actionType}
        assertThat(f.getActionsJson()).contains("\"key\":\"RERUN_MORE_MEMORY\"")
                .contains("\"actionType\":\"APPLY_FIX_RERUN_MORE_MEMORY\"")
                .contains("\"label\":\"调大内存重跑\"");
    }

    @Test
    void inspect_skipsAlreadyHandled_andDoesNotDiagnose() {
        when(instanceRepository.findByState("FAILED")).thenReturn(List.of(failed(INST)));
        when(findingService.exists("TASK_FAILURE", "TASK_INSTANCE", INST.toString())).thenReturn(true);

        List<Finding> out = inspector.inspect();

        assertThat(out).isEmpty();
        verify(diagnosisService, never()).diagnoseInstance(any());
    }

    @Test
    void mapActions_fallsBackToRerun_whenBlankOrUnparseable() {
        assertThat(inspector.mapActions(null)).contains("\"key\":\"RERUN\"");
        assertThat(inspector.mapActions("not-json")).contains("\"actionType\":\"APPLY_FIX_RERUN\"");
    }
}
