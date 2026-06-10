package com.dataweave.api.application;

import com.dataweave.api.interfaces.dto.PageContext;
import com.dataweave.master.application.DiagnosisService;
import com.dataweave.master.application.FleetService;
import com.dataweave.master.application.LineageService;
import com.dataweave.master.application.MetricService;
import com.dataweave.master.application.SqlExecutionService;
import com.dataweave.master.application.TaskService;
import com.dataweave.master.domain.TaskDiagnosis;
import com.dataweave.master.domain.WorkerNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IntentRouterIntentTest {

    @Mock private MetricService metricService;
    @Mock private LineageService lineageService;
    @Mock private TaskService taskService;
    @Mock private SqlExecutionService sqlExecutionService;
    @Mock private LlmClient llmClient;
    @Mock private FleetService fleetService;
    @Mock private DiagnosisService diagnosisService;

    private ObjectMapper objectMapper;
    private IntentRouter router;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        router = new IntentRouter(metricService, lineageService, taskService,
                sqlExecutionService, llmClient, fleetService, diagnosisService, objectMapper);
    }

    @Test
    void diagnosisIntent_returnsDiagnosisWithCustomEventName() {
        TaskDiagnosis diagnosis = new TaskDiagnosis();
        diagnosis.setId(42L);
        diagnosis.setTitle("OOM on node-1");
        diagnosis.setRootCause("Executor 内存不足，heap 耗尽");
        diagnosis.setWorkerNodeCode("node-1");
        diagnosis.setContextJson("{\"taskName\":\"GMV统计\"}");
        diagnosis.setSuggestionsJson("[{\"action\":\"RERUN\",\"label\":\"原地重跑\"},{\"action\":\"RERUN_MORE_MEMORY\",\"label\":\"加大内存重跑\"}]");
        diagnosis.setStatus("OPEN");

        when(diagnosisService.diagnoseLatestFailure()).thenReturn(Optional.of(diagnosis));

        AgentReply reply = router.route("帮我诊断为什么失败");

        assertThat(reply.customEventName()).isEqualTo("dataweave.diagnosis");
        assertThat(reply.structured()).isNotNull();
        assertThat(reply.structured().get("kind")).isEqualTo("diagnosis");
        assertThat(reply.structured().get("id")).isEqualTo(42L);
        assertThat(reply.structured().get("rootCause")).isEqualTo("Executor 内存不足，heap 耗尽");
        assertThat(reply.structured().get("workerNodeCode")).isEqualTo("node-1");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> suggestions =
                (List<Map<String, Object>>) reply.structured().get("suggestions");
        assertThat(suggestions).hasSize(2);
        assertThat(suggestions.get(0).get("action")).isEqualTo("RERUN");
        assertThat(suggestions.get(0).get("label")).isEqualTo("原地重跑");

        assertThat(reply.markdown()).contains("根因");
        assertThat(reply.markdown()).contains("Executor 内存不足，heap 耗尽");
        assertThat(reply.markdown()).contains("原地重跑");
    }

    @Test
    void diagnosisIntent_withPageContextInstanceId_diagnosesThatInstance_noRestate() {
        // 失败实例详情页问「为什么挂了」——上下文带 instanceId，无需复述对象（缺口①）
        TaskDiagnosis diagnosis = new TaskDiagnosis();
        diagnosis.setId(7L);
        diagnosis.setTitle("OOM on node-3");
        diagnosis.setRootCause("内存溢出");
        when(diagnosisService.diagnoseInstance(100L)).thenReturn(diagnosis);

        PageContext ctx = new PageContext("/ops", "/ops", null, "100", null);
        AgentReply reply = router.route("为什么挂了", ctx);

        assertThat(reply.customEventName()).isEqualTo("dataweave.diagnosis");
        assertThat(reply.structured().get("id")).isEqualTo(7L);
        // 用了上下文的 instanceId，而非 diagnoseLatestFailure
        org.mockito.Mockito.verify(diagnosisService).diagnoseInstance(100L);
        org.mockito.Mockito.verify(diagnosisService, org.mockito.Mockito.never()).diagnoseLatestFailure();
    }

    @Test
    void diagnosisIntent_noFailure_returnsTextOnly() {
        when(diagnosisService.diagnoseLatestFailure()).thenReturn(Optional.empty());

        AgentReply reply = router.route("排查一下报错原因");

        assertThat(reply.customEventName()).isNull();
        assertThat(reply.structured()).isNull();
        assertThat(reply.markdown()).contains("当前没有失败的任务实例可诊断");
    }

    @Test
    void fleetIntent_returnsFleetWithTwoRows() {
        WorkerNode n1 = new WorkerNode();
        n1.setNodeCode("node-1");
        n1.setStatus("ONLINE");
        n1.setCpu(45.2);
        n1.setMem(60.0);
        n1.setDisk(30.5);
        n1.setLoadAvg(1.5);
        n1.setRunningTasks(3);

        WorkerNode n2 = new WorkerNode();
        n2.setNodeCode("node-2");
        n2.setStatus("ONLINE");
        n2.setCpu(80.0);
        n2.setMem(90.0);
        n2.setDisk(50.0);
        n2.setLoadAvg(3.2);
        n2.setRunningTasks(7);

        when(fleetService.nodes()).thenReturn(List.of(n1, n2));

        AgentReply reply = router.route("看看集群机器状态");

        assertThat(reply.customEventName()).isEqualTo("dataweave.fleet");
        assertThat(reply.structured()).isNotNull();
        assertThat(reply.structured().get("kind")).isEqualTo("fleet");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows =
                (List<Map<String, Object>>) reply.structured().get("rows");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("nodeCode")).isEqualTo("node-1");
        assertThat(rows.get(1).get("nodeCode")).isEqualTo("node-2");

        @SuppressWarnings("unchecked")
        List<String> columns = (List<String>) reply.structured().get("columns");
        assertThat(columns).containsExactly("nodeCode", "status", "cpu", "mem", "disk", "loadAvg", "runningTasks");

        // Markdown 表格包含节点名
        assertThat(reply.markdown()).contains("node-1");
        assertThat(reply.markdown()).contains("node-2");
    }
}
