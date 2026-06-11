package com.dataweave.master.application;

import com.dataweave.master.domain.TaskDef;
import com.dataweave.master.domain.TaskDefRepository;
import com.dataweave.master.domain.TaskDiagnosis;
import com.dataweave.master.domain.TaskDiagnosisRepository;
import com.dataweave.master.domain.TaskInstance;
import com.dataweave.master.domain.TaskInstanceRepository;
import com.dataweave.master.domain.WorkerNode;
import com.dataweave.master.domain.WorkerNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 诊断服务（缺口②补课）：上下文采集内容、按 taskInstanceId 幂等。
 */
@ExtendWith(MockitoExtension.class)
class DiagnosisServiceTest {

    @Mock
    private TaskInstanceRepository instanceRepository;
    @Mock
    private TaskDefRepository taskDefRepository;
    @Mock
    private WorkerNodeRepository nodeRepository;
    @Mock
    private TaskDiagnosisRepository diagnosisRepository;
    @Mock
    private DiagnosisAnalyzer analyzer;
    @Mock
    private GatedActionService gatedActionService;

    private DiagnosisService service;

    @BeforeEach
    void setUp() {
        service = new DiagnosisService(instanceRepository, taskDefRepository,
                nodeRepository, diagnosisRepository, analyzer, gatedActionService);
    }

    @Test
    void diagnoseInstance_idempotent_returnsExistingWithoutReanalyzing() {
        TaskDiagnosis existing = new TaskDiagnosis();
        existing.setId(5L);
        when(diagnosisRepository.findFirstByTaskInstanceIdOrderByIdDesc(java.util.UUID.fromString("01910000-0010-7000-8000-000000000100")))
                .thenReturn(Optional.of(existing));

        TaskDiagnosis result = service.diagnoseInstance(java.util.UUID.fromString("01910000-0010-7000-8000-000000000100"));

        assertThat(result).isSameAs(existing);
        verify(analyzer, never()).analyze(any(), any(), any());
        verify(diagnosisRepository, never()).save(any());
    }

    @Test
    void diagnoseInstance_collectsContext_andPersistsAnalysis() {
        when(diagnosisRepository.findFirstByTaskInstanceIdOrderByIdDesc(java.util.UUID.fromString("01910000-0010-7000-8000-000000000100")))
                .thenReturn(Optional.empty());

        TaskInstance instance = new TaskInstance();
        instance.setId(java.util.UUID.fromString("01910000-0010-7000-8000-000000000100"));
        instance.setTaskId(10L);
        instance.setWorkflowInstanceId(java.util.UUID.fromString("01910000-0001-7000-8000-000000000007"));
        instance.setWorkerNodeCode("node-3");
        when(instanceRepository.findById(java.util.UUID.fromString("01910000-0010-7000-8000-000000000100"))).thenReturn(Optional.of(instance));

        WorkerNode node = new WorkerNode();
        node.setNodeCode("node-3");
        when(nodeRepository.findByNodeCode("node-3")).thenReturn(Optional.of(node));

        TaskDef task = new TaskDef();
        task.setId(10L);
        when(taskDefRepository.findById(10L)).thenReturn(Optional.of(task));

        when(analyzer.analyze(instance, node, task)).thenReturn(new DiagnosisAnalyzer.Analysis(
                "OOM@node-3", "executor 内存溢出", "{\"node\":\"node-3\"}", "[{\"label\":\"调大内存\"}]"));
        when(diagnosisRepository.save(any(TaskDiagnosis.class))).thenAnswer(inv -> inv.getArgument(0));

        TaskDiagnosis result = service.diagnoseInstance(java.util.UUID.fromString("01910000-0010-7000-8000-000000000100"));

        // 采集了正确的上下文对象
        verify(analyzer).analyze(instance, node, task);

        ArgumentCaptor<TaskDiagnosis> cap = ArgumentCaptor.forClass(TaskDiagnosis.class);
        verify(diagnosisRepository).save(cap.capture());
        TaskDiagnosis saved = cap.getValue();
        assertThat(saved.getTaskInstanceId()).isEqualTo(java.util.UUID.fromString("01910000-0010-7000-8000-000000000100"));
        assertThat(saved.getWorkflowInstanceId()).isEqualTo(java.util.UUID.fromString("01910000-0001-7000-8000-000000000007"));
        assertThat(saved.getTaskId()).isEqualTo(10L);
        assertThat(saved.getWorkerNodeCode()).isEqualTo("node-3");
        assertThat(saved.getTitle()).isEqualTo("OOM@node-3");
        assertThat(saved.getRootCause()).isEqualTo("executor 内存溢出");
        assertThat(saved.getContextJson()).contains("node-3");
        assertThat(saved.getSuggestionsJson()).contains("调大内存");
        assertThat(saved.getStatus()).isEqualTo("OPEN");
        assertThat(result).isSameAs(saved);
    }
}
