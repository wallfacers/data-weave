package com.dataweave.api.application;

import com.dataweave.api.interfaces.dto.RunAgentInput;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AG-UI 事件编排器：把意图路由结果转为 AG-UI 事件序列，按 SSE 流式输出。
 *
 * <p>事件序列（type 用 SCREAMING_SNAKE_CASE）：
 * RUN_STARTED -> TEXT_MESSAGE_START -> N×TEXT_MESSAGE_CONTENT -> TEXT_MESSAGE_END
 * -> [CUSTOM dataweave.result] -> RUN_FINISHED。
 */
@Service
public class AguiOrchestrator {

    private final IntentRouter intentRouter;
    private final ObjectMapper objectMapper; // Jackson 3: tools.jackson.databind.ObjectMapper

    public AguiOrchestrator(IntentRouter intentRouter, ObjectMapper objectMapper) {
        this.intentRouter = intentRouter;
        this.objectMapper = objectMapper;
    }

    public Flux<ServerSentEvent<String>> run(RunAgentInput input) {
        String threadId = input.getThreadId() != null ? input.getThreadId() : UUID.randomUUID().toString();
        String runId = input.getRunId() != null ? input.getRunId() : UUID.randomUUID().toString();
        String userMessage = input.lastUserContent();
        String messageId = UUID.randomUUID().toString();

        // 路由（含阻塞 JDBC 查询）放到 boundedElastic，避免阻塞事件循环线程
        return Flux.defer(() -> {
                    AgentReply reply = intentRouter.route(userMessage);
                    return Flux.fromIterable(buildEvents(threadId, runId, messageId, reply));
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private List<ServerSentEvent<String>> buildEvents(String threadId, String runId,
                                                      String messageId, AgentReply reply) {
        List<ServerSentEvent<String>> events = new ArrayList<>();

        // 1. RUN_STARTED
        events.add(sse(map("type", "RUN_STARTED", "threadId", threadId, "runId", runId)));

        // 2. TEXT_MESSAGE_START
        events.add(sse(map("type", "TEXT_MESSAGE_START", "messageId", messageId, "role", "assistant")));

        // 3. TEXT_MESSAGE_CONTENT（切片成多个 delta；拼起来是完整 Markdown 回复）
        for (String delta : splitDeltas(reply.markdown())) {
            events.add(sse(map("type", "TEXT_MESSAGE_CONTENT", "messageId", messageId, "delta", delta)));
        }

        // 4. TEXT_MESSAGE_END
        events.add(sse(map("type", "TEXT_MESSAGE_END", "messageId", messageId)));

        // 5. CUSTOM 结构化结果（若有）
        if (reply.structured() != null) {
            events.add(sse(map("type", "CUSTOM", "name", "dataweave.result", "value", reply.structured())));
        }

        // 6. RUN_FINISHED
        Map<String, Object> finished = new LinkedHashMap<>();
        finished.put("type", "RUN_FINISHED");
        finished.put("threadId", threadId);
        finished.put("runId", runId);
        finished.put("outcome", map("type", "success"));
        events.add(sse(finished));

        return events;
    }

    /** 把完整 Markdown 切成若干 delta 片段（按行/段），模拟流式增量。 */
    private List<String> splitDeltas(String markdown) {
        List<String> deltas = new ArrayList<>();
        if (markdown == null || markdown.isEmpty()) {
            deltas.add("");
            return deltas;
        }
        String[] lines = markdown.split("(?<=\n)");
        StringBuilder buf = new StringBuilder();
        for (String line : lines) {
            buf.append(line);
            // 每累积一定长度或遇到段落结束就 flush 一个 delta
            if (buf.length() >= 60 || line.endsWith("\n\n")) {
                deltas.add(buf.toString());
                buf.setLength(0);
            }
        }
        if (buf.length() > 0) {
            deltas.add(buf.toString());
        }
        if (deltas.isEmpty()) {
            deltas.add(markdown);
        }
        return deltas;
    }

    private ServerSentEvent<String> sse(Map<String, Object> payload) {
        return ServerSentEvent.<String>builder()
                .data(writeJson(payload))
                .build();
    }

    private String writeJson(Object o) {
        // Jackson 3 ObjectMapper: writeValueAsString 不再抛受检异常
        return objectMapper.writeValueAsString(o);
    }

    private Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }
}
