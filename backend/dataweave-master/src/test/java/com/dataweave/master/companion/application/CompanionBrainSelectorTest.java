package com.dataweave.master.companion.application;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;

import com.dataweave.master.companion.domain.ChatCallbacks;
import com.dataweave.master.companion.domain.CompanionBrain;
import com.dataweave.master.companion.domain.PatrolResult;
import com.dataweave.master.companion.domain.PatrolRoutine;
import com.dataweave.master.companion.infrastructure.MockBrain;
import com.dataweave.master.companion.infrastructure.WorkhorseBrainClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 大脑选择策略测试：workhorse 健康 → 巡检/对话都用 workhorse；不健康 → 巡检降级 mock、对话拒绝。
 * 用进程内 {@link HttpServer} 模拟健康，死 URL 模拟不可用（无真实 workhorse 依赖）。
 */
class CompanionBrainSelectorTest {

    @Test
    void routing_workhorseWhenHealthy_mockAndRejectWhenNot() {
        HttpServer server = newServer200();
        try {
            String base = "http://localhost:" + server.getAddress().getPort();
            WorkhorseBrainClient wh = new WorkhorseBrainClient(base, "companion", 120);
            MockBrain mock = new MockBrain();
            CompanionBrainSelector sel = new CompanionBrainSelector(wh, mock);

            assertThat(sel.healthy()).isTrue();
            assertThat(sel.forPatrol()).isSameAs(wh);
            assertThat(sel.forChat()).contains(wh);
        } finally {
            server.stop(0);
        }

        // 死 URL → workhorse 不可用
        WorkhorseBrainClient dead = new WorkhorseBrainClient("http://127.0.0.1:1", "companion", 120);
        MockBrain mock = new MockBrain();
        CompanionBrainSelector sel = new CompanionBrainSelector(dead, mock);
        assertThat(sel.healthy()).isFalse();
        assertThat(sel.forPatrol()).isSameAs(mock);     // 巡检降级 mock
        assertThat(sel.forChat()).isEmpty();            // 对话拒绝（调用方抛 brain_unavailable）
    }

    @Test
    void mockBrain_patrolFailsAndChatCannedReply() {
        MockBrain mock = new MockBrain();
        assertThat(mock.healthy()).isTrue();
        assertThat(mock.name()).isEqualTo("mock");

        // 巡检 → failed（交 PatrolService 兜底未完成汇报）
        PatrolRoutine routine = new PatrolRoutine(1, 1, 1, "TASK_FAILURE", true, "0 */15 * * * *",
                null, 120, null, null, null, null, 0);
        PatrolResult r = mock.runPatrol(routine, null, 120);
        assertThat(r.ok()).isFalse();

        // 对话 → 固定降级话术
        AtomicReference<String> delta = new AtomicReference<>();
        AtomicReference<String> end = new AtomicReference<>();
        ChatCallbacks cb = new ChatCallbacks() {
            @Override public void onDelta(String chunk) { delta.set(chunk); }
            @Override public void onEnd(String fullText, boolean interrupted) { end.set(fullText); }
        };
        CompanionBrain brain = mock;
        String reply = brain.openChat(1, "ctx", cb).send("你好");
        assertThat(reply).contains("Vega");
        assertThat(delta.get()).isEqualTo(reply);
        assertThat(end.get()).isEqualTo(reply);
    }

    private HttpServer newServer200() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                byte[] resp = "OK".getBytes();
                exchange.sendResponseHeaders(200, resp.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
