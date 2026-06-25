package com.dataweave.api;

import com.dataweave.api.application.bridge.WorkhorseClient;
import com.dataweave.api.application.bridge.WorkhorseEvent;
import com.dataweave.api.application.bridge.WorkhorseHealth;
import com.dataweave.master.domain.AgentRun;
import com.dataweave.master.domain.AgentRunRepository;
import com.dataweave.master.domain.AgentStep;
import com.dataweave.master.domain.AgentStepRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * workhorse 模式：stub workhorse SSE 客户端驱动桥接层；断言与 mock 模式同套 AG-UI 事件序列
 * （RUN_STARTED…RUN_FINISHED），并验证工具调用落 agent_step + PENDING_APPROVAL → CUSTOM 审批卡片。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"agent.mode=workhorse"})
@ActiveProfiles("h2")
class AguiWorkhorseModeTest {

    @TestConfiguration
    static class StubConfig {
        /** probe gating：显式 workhorse 模式下注入恒健康，绕过对真实 sidecar 的 HTTP 探测。 */
        @Bean
        @Primary
        WorkhorseHealth stubWorkhorseHealth() {
            return () -> true;
        }

        @Bean
        @Primary
        WorkhorseClient stubWorkhorseClient() {
            return new WorkhorseClient() {
                @Override
                public String createSession(String instructions, Map<String, Object> metadata) {
                    return "wh-stub-session";
                }

                @Override
                public Flux<WorkhorseEvent> sendMessage(String sessionId, String content) {
                    return Flux.just(
                            WorkhorseEvent.text("正在为你查询集群…\n"),
                            WorkhorseEvent.toolStart("tu_1", "query_fleet", "{}"),
                            WorkhorseEvent.toolDone("tu_1", "[{\"nodeCode\":\"node-1\"}]", false),
                            WorkhorseEvent.permission("tu_1", "allow", "rule"),
                            WorkhorseEvent.text("集群正常。现在尝试一个受控命令。"),
                            WorkhorseEvent.toolStart("tu_2", "node_exec",
                                    "{\"nodeCode\":\"node-1\",\"command\":\"df -h ; rm -rf /tmp\"}"),
                            WorkhorseEvent.toolDone("tu_2",
                                    "{\"outcome\":\"PENDING_APPROVAL\",\"approvalId\":42,\"level\":\"L2\","
                                            + "\"summary\":\"在 node-1 执行 df -h ; rm -rf /tmp\",\"message\":\"待人工批准\"}",
                                    false),
                            WorkhorseEvent.done());
                }
            };
        }
    }

    @LocalServerPort
    private int port;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private WebTestClient client;

    @org.springframework.beans.factory.annotation.Autowired
    private AgentRunRepository runRepository;
    @org.springframework.beans.factory.annotation.Autowired
    private AgentStepRepository stepRepository;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .responseTimeout(java.time.Duration.ofSeconds(15)).build();
    }

    @Test
    void workhorseMode_streamsAguiSequence_recordsSteps_andEmitsApprovalCard() {
        String body = client.post().uri("/agui")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(Map.of("threadId", "wh-t1", "runId", "wh-r1",
                        "messages", List.of(Map.of("role", "user", "content", "看看集群然后跑个命令"))))
                .exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();

        List<Map<String, Object>> events = parse(body);
        List<String> types = events.stream().map(e -> (String) e.get("type")).toList();

        // 同套 AG-UI 序列断言
        assertThat(types.get(0)).isEqualTo("RUN_STARTED");
        assertThat(types.get(types.size() - 1)).isEqualTo("RUN_FINISHED");
        assertThat(types).filteredOn("TEXT_MESSAGE_START"::equals).hasSize(1);
        assertThat(types).filteredOn("TEXT_MESSAGE_END"::equals).hasSize(1);
        assertThat(types).filteredOn("TEXT_MESSAGE_CONTENT"::equals).hasSizeGreaterThanOrEqualTo(2);

        // 工具返回 PENDING_APPROVAL → CUSTOM dataweave.approval 审批卡片
        Map<String, Object> approval = events.stream()
                .filter(e -> "CUSTOM".equals(e.get("type")) && "dataweave.approval".equals(e.get("name")))
                .findFirst().orElse(null);
        assertThat(approval).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> card = (Map<String, Object>) approval.get("value");
        assertThat(card).containsEntry("level", "L2");
        assertThat(String.valueOf(card.get("approvalId"))).isEqualTo("42");

        // 审计：run 落库 + 两个工具 step（tu_1 含决议 allow/rule）
        AgentRun run = runRepository.findAll().iterator().next();
        assertThat(run.getState()).isEqualTo("FINISHED");
        List<AgentStep> steps = stepRepository.findByRunIdOrderBySeqAsc(run.getId());
        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).getToolName()).isEqualTo("query_fleet");
        assertThat(steps.get(0).getDecision()).isEqualTo("allow");
        assertThat(steps.get(0).getDecisionSource()).isEqualTo("rule");
        assertThat(steps.get(1).getToolName()).isEqualTo("node_exec");
    }

    private List<Map<String, Object>> parse(String sseBody) {
        List<Map<String, Object>> events = new ArrayList<>();
        if (sseBody == null) {
            return events;
        }
        for (String line : sseBody.split("\n")) {
            String t = line.trim();
            if (t.startsWith("data:")) {
                try {
                    events.add(objectMapper.readValue(t.substring(5).trim(), Map.class));
                } catch (Exception ignored) {
                    // skip
                }
            }
        }
        return events;
    }
}
