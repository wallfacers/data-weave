package com.dataweave.master.application;

import com.dataweave.master.domain.TaskDefRepository;
import com.dataweave.master.domain.TaskDiagnosis;
import com.dataweave.master.domain.TaskDiagnosisRepository;
import com.dataweave.master.domain.TaskInstanceRepository;
import com.dataweave.master.domain.WorkerNodeRepository;
import com.dataweave.master.i18n.Messages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * applyFix 迁入闸门（缺口③）：经 GatedActionService 留痕，不再有直接执行路径。
 */
@ExtendWith(MockitoExtension.class)
class DiagnosisServiceApplyFixTest {

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

    private DiagnosisService diagnosisService;

    private Messages realMessages() {
        ReloadableResourceBundleMessageSource ms = new ReloadableResourceBundleMessageSource();
        ms.setBasename("classpath:messages");
        ms.setDefaultEncoding("UTF-8");
        ms.setFallbackToSystemLocale(false);
        return new Messages(ms);
    }

    @BeforeEach
    void setUp() {
        diagnosisService = new DiagnosisService(instanceRepository, taskDefRepository,
                nodeRepository, diagnosisRepository, analyzer, gatedActionService, realMessages());
    }

    @Test
    void applyFix_goesThroughGate_withAuditableRequest() {
        TaskDiagnosis d = new TaskDiagnosis();
        d.setId(7L);
        d.setTaskId(100L);
        d.setWorkerNodeCode("node-3");
        when(diagnosisRepository.findById(7L)).thenReturn(Optional.of(d));
        when(gatedActionService.submit(any(), any())).thenReturn(
                new GateResult(GateResult.Outcome.EXECUTED, 1L, "L1", "已迁移重跑成功", null, false, java.util.UUID.fromString("01910000-0010-7000-8000-000000000088")));

        DiagnosisService.FixResult r = diagnosisService.applyFix(7L, "MIGRATE_NODE", "agent", "AGENT",
                Messages.DEFAULT_LOCALE);

        assertThat(r.success()).isTrue();
        assertThat(r.message()).isEqualTo("已迁移重跑成功");
        assertThat(r.newInstanceId()).isEqualTo(java.util.UUID.fromString("01910000-0010-7000-8000-000000000088"));

        ArgumentCaptor<ActionRequest> cap = ArgumentCaptor.forClass(ActionRequest.class);
        org.mockito.Mockito.verify(gatedActionService).submit(cap.capture(), any());
        ActionRequest req = cap.getValue();
        assertThat(req.toolName()).isEqualTo("apply_fix");
        assertThat(req.actionType()).isEqualTo("APPLY_FIX_MIGRATE_NODE");
        assertThat(req.targetType()).isEqualTo("DIAGNOSIS");
        assertThat(req.targetId()).isEqualTo("7");
        assertThat(req.actor()).isEqualTo("agent");
        assertThat(req.actorSource()).isEqualTo("AGENT");
        assertThat(req.ownedByPlatform()).isTrue();
    }

    @Test
    void applyFix_missingDiagnosis_returnsFailure() {
        when(diagnosisRepository.findById(99L)).thenReturn(Optional.empty());
        DiagnosisService.FixResult r = diagnosisService.applyFix(99L, "RERUN");
        assertThat(r.success()).isFalse();
        assertThat(r.message()).contains("未找到诊断记录");
    }
}
