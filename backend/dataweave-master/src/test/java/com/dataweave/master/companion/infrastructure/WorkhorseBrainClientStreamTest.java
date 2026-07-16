package com.dataweave.master.companion.infrastructure;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import com.dataweave.master.companion.infrastructure.WorkhorseBrainClient.BrainErrorException;
import com.dataweave.master.companion.infrastructure.WorkhorseBrainClient.BrainTimeoutException;
import com.dataweave.master.companion.infrastructure.WorkhorseBrainClient.TurnOutcome;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WorkhorseBrainClient 真实流式路径测试（B1+M2 修复验证）：进程内假服务端吐 SSE 事件，
 * 验证 streamTurn 对 {@code done}/{@code interrupted}/{@code error}/超时的真实处理。
 *
 * <p>对冲假绿：{@code CompanionChatServiceTest.cancel_interruptsStream} 用 scripted brain 绕过了
 * 真实 streamTurn 路径，本测试直接走真 {@link WorkhorseBrainClient}——
 * interrupted→{@code TurnOutcome.interrupted=true}、error→抛 {@link BrainErrorException}、
 * 卡住→poll 超时抛 {@link BrainTimeoutException}。
 */
class WorkhorseBrainClientStreamTest {

    private static HttpServer server;
    private static WorkhorseBrainClient client;
    private static final AtomicInteger postCount = new AtomicInteger();

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(8));   // 多线程：GET 句柄挂起时不饿死 POST
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        client = new WorkhorseBrainClient(base, "companion", 120, "", "");
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    /** GET 流吐完整 SSE 帧序列后即关流；POST 触发回 202。 */
    private HttpHandler sseHandler(String frames) {
        return (HttpExchange exchange) -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                postCount.incrementAndGet();
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(frames.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
        };
    }

    @Test
    void done_returnsFullTextNotInterrupted() throws Exception {
        server.createContext("/v1/sessions/s1/stream", sseHandler(
                "event: assistant_text_delta\ndata: {\"text\":\"Hello \"}\n\n" +
                "event: assistant_text_delta\ndata: {\"text\":\"Vega\"}\n\n" +
                "event: assistant_text_done\ndata: {}\n\n"));
        TurnOutcome out = client.streamTurn("s1", "hi", 10, null);
        assertThat(out.text()).isEqualTo("Hello Vega");
        assertThat(out.interrupted()).isFalse();
        assertThat(postCount.get()).isEqualTo(1);   // POST 触发本轮
    }

    @Test
    void interrupted_returnsHalfTextFlaggedInterrupted() throws Exception {
        server.createContext("/v1/sessions/s2/stream", sseHandler(
                "event: assistant_text_delta\ndata: {\"text\":\"半截\"}\n\n" +
                "event: interrupted\ndata: {}\n\n"));
        TurnOutcome out = client.streamTurn("s2", "hi", 10, null);
        assertThat(out.text()).isEqualTo("半截");
        assertThat(out.interrupted()).isTrue();   // B1：打断语义保真，不再被当 done 吞成 false
    }

    @Test
    void error_eventThrowsNotSwallowed() {
        server.createContext("/v1/sessions/s3/stream", sseHandler(
                "event: error\ndata: {\"message\":\"模型过载\"}\n\n"));
        // B1：error 不再落入 default 被吞（070 同款前科）——解析 message 抛 BrainErrorException
        assertThatThrownBy(() -> client.streamTurn("s3", "hi", 10, null))
                .isInstanceOf(BrainErrorException.class)
                .hasMessageContaining("模型过载");
    }

    @Test
    void error_eventWithoutMessageFallsBackToRaw() {
        server.createContext("/v1/sessions/s4/stream", sseHandler(
                "event: error\ndata: {}\n\n"));
        assertThatThrownBy(() -> client.streamTurn("s4", "hi", 10, null))
                .isInstanceOf(BrainErrorException.class);   // 无 message → 回退原始 data，仍抛
    }

    /**
     * 卡死读超时（M2）：裸 {@link ServerSocket} 收 GET 后写响应头 + 一个 delta 帧再<b>保持 TCP 不关</b>
     * （HttpServer 会自动关流测不出真阻塞，故用裸 socket 完全掌控 EOF）。reader 读到 delta 后阻塞在
     * 下一行 readLine → 主线程 poll 1s 超时抛 {@link BrainTimeoutException}，reader 仅困于 daemon 线程不耗尽调用方线程池。
     */
    @Test
    void stuckStream_pollTimesOut() throws Exception {
        try (ServerSocket ss = new ServerSocket()) {
            ss.bind(new InetSocketAddress("127.0.0.1", 0));
            int port = ss.getLocalPort();
            Thread srv = new Thread(() -> rawHoldServer(ss), "raw-hold");
            srv.setDaemon(true);
            srv.start();
            WorkhorseBrainClient c = new WorkhorseBrainClient("http://127.0.0.1:" + port, "companion", 120, "", "");
            long t0 = System.currentTimeMillis();
            assertThatThrownBy(() -> c.streamTurn("s5", "hi", 1, null))
                    .isInstanceOf(BrainTimeoutException.class);
            long elapsed = System.currentTimeMillis() - t0;
            assertThat(elapsed).isLessThan(3_000L);   // 1s 超时触发，绝不卡到 4s 的 hold
        }
    }

    /** 裸服务端：conn1=GET（写头+delta 后保活 4s），conn2=POST（即回 202）。 */
    private static void rawHoldServer(ServerSocket ss) {
        try {
            Socket get = ss.accept();   // GET 连接
            Thread getter = new Thread(() -> holdGet(get), "raw-get");
            getter.setDaemon(true);
            getter.start();
            Socket post = ss.accept();   // POST 连接
            quick202(post);
        } catch (Exception ignore) { /* 测试结束关 socket */ }
    }

    private static void holdGet(Socket s) {
        try (Socket c = s) {
            drainHeaders(c.getInputStream());   // 读掉 GET 请求头
            OutputStream out = c.getOutputStream();
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n" +
                    "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            String body = "event: assistant_text_delta\ndata: {\"text\":\"a\"}\n\n";
            out.write((Integer.toHexString(body.length( )) + "\r\n" + body + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            Thread.sleep(4_000);   // 保持 TCP 不关、不发终结 chunk → reader 阻塞在下一行 readLine
        } catch (Exception ignore) { /* 忽略 */ }
    }

    private static void quick202(Socket s) {
        try (Socket c = s) {
            drainHeaders(c.getInputStream());
            c.getOutputStream().write("HTTP/1.1 202 Accepted\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            c.getOutputStream().flush();
        } catch (Exception ignore) { /* 忽略 */ }
    }

    private static void drainHeaders(InputStream in) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String l;
        while ((l = r.readLine()) != null && !l.isEmpty()) { /* 丢弃请求头 */ }
    }
}
