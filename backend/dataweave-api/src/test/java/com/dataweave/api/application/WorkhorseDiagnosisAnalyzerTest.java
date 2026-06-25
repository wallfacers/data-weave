package com.dataweave.api.application;

import com.dataweave.api.application.bridge.WorkhorseBridge;
import com.dataweave.api.application.bridge.WorkhorseHealth;
import com.dataweave.master.application.DiagnosisAnalyzer.Analysis;
import com.dataweave.master.application.DiagnosisAnalyzer.Telemetry;
import com.dataweave.master.application.MockDiagnosisAnalyzer;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link WorkhorseDiagnosisAnalyzer} 单测：健康时 LLM JSON → Analysis；
 * 不健康 / 不可解析时回落 {@link MockDiagnosisAnalyzer}。
 */
class WorkhorseDiagnosisAnalyzerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Telemetry tel = new Telemetry(2, 3);
    private final Analysis mockResult = new Analysis("mock-title", "mock-rc", "{}", "[]");

    private WorkhorseDiagnosisAnalyzer analyzer(WorkhorseBridge bridge, WorkhorseHealth health,
                                                MockDiagnosisAnalyzer fallback) {
        return new WorkhorseDiagnosisAnalyzer(bridge, health, fallback, objectMapper, 5000);
    }

    @Test
    void healthy_parsesLlmJson_intoAnalysis() {
        WorkhorseBridge bridge = mock(WorkhorseBridge.class);
        WorkhorseHealth health = () -> true;
        MockDiagnosisAnalyzer fallback = mock(MockDiagnosisAnalyzer.class);
        when(bridge.runHeadless(any(), any())).thenReturn(Mono.just(
                "{\"title\":\"OOM 根因\",\"rootCause\":\"node-3 内存 95%\","
                        + "\"suggestions\":[{\"action\":\"MIGRATE_NODE\",\"label\":\"迁移到 node-5\"}]}"));

        Analysis out = analyzer(bridge, health, fallback)
                .analyze(null, null, null, tel, Locale.SIMPLIFIED_CHINESE);

        assertThat(out.title()).isEqualTo("OOM 根因");
        assertThat(out.rootCause()).isEqualTo("node-3 内存 95%");
        assertThat(out.suggestionsJson()).contains("MIGRATE_NODE").contains("迁移到 node-5");
        // contextJson 用真实遥测自组装（不信模型）
        assertThat(out.contextJson()).contains("\"concurrentTasks\":2").contains("\"history7d\":3");
        verify(fallback, never()).analyze(any(), any(), any(), any(), any());
    }

    @Test
    void unhealthy_fallsBackToMock_withoutCallingBridge() {
        WorkhorseBridge bridge = mock(WorkhorseBridge.class);
        WorkhorseHealth health = () -> false;
        MockDiagnosisAnalyzer fallback = mock(MockDiagnosisAnalyzer.class);
        when(fallback.analyze(any(), any(), any(), any(), any())).thenReturn(mockResult);

        Analysis out = analyzer(bridge, health, fallback)
                .analyze(null, null, null, tel, Locale.SIMPLIFIED_CHINESE);

        assertThat(out).isEqualTo(mockResult);
        verify(bridge, never()).runHeadless(any(), any());
    }

    @Test
    void unparseable_fallsBackToMock() {
        WorkhorseBridge bridge = mock(WorkhorseBridge.class);
        WorkhorseHealth health = () -> true;
        MockDiagnosisAnalyzer fallback = mock(MockDiagnosisAnalyzer.class);
        when(fallback.analyze(any(), any(), any(), any(), any())).thenReturn(mockResult);
        when(bridge.runHeadless(any(), any())).thenReturn(Mono.just("抱歉，我现在无法给出诊断。"));

        Analysis out = analyzer(bridge, health, fallback)
                .analyze(null, null, null, tel, Locale.SIMPLIFIED_CHINESE);

        assertThat(out).isEqualTo(mockResult);
    }
}
