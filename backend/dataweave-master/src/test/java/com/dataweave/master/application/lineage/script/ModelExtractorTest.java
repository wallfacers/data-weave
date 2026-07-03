package com.dataweave.master.application.lineage.script;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpServer;
import com.dataweave.master.domain.lineage.Source;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 041 T034：ModelExtractor —— 正常转边（带 modelVersion）/ 幻觉校验拒收 /
 * 超时·5xx·未配置 → 旁路或空产物零异常（FR-012/FR-013）。
 */
class ModelExtractorTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String startSidecar(String extractJson, int status, long delayMs) throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/health", ex -> {
            byte[] b = "{\"status\":\"UP\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        });
        server.createContext("/extract", ex -> {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            byte[] b = extractJson.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static ScriptSource src(String content) {
        return new ScriptSource(1, 1, 9L, "PYTHON", content, null, null);
    }

    @Test
    void validOutputBecomesModelEdgesWithVersion() throws Exception {
        String url = startSidecar("""
                {"modelVersion":"m@v1",
                 "reads":[{"table":"ods.users","columns":null}],
                 "writes":[{"table":"dw.users_clean","columns":["id"]}]}""", 200, 0);
        ModelExtractor extractor = new ModelExtractor(url, 2000);
        assertThat(extractor.supports("PYTHON")).isTrue();
        ScriptExtraction ex = extractor.extract(src(
                "df = load(\"ods.users\")\nwrite_to_warehouse(df, \"dw.users_clean\")"));
        assertThat(ex.channel()).isEqualTo(Source.SCRIPT_MODEL);
        assertThat(ex.modelVersion()).isEqualTo("m@v1");
        assertThat(ex.reads()).containsExactly("ods.users");
        assertThat(ex.writes()).containsExactly("dw.users_clean");
    }

    @Test
    void hallucinatedTableRejectedWithHint() throws Exception {
        String url = startSidecar("""
                {"modelVersion":"m@v1",
                 "reads":[],
                 "writes":[{"table":"dw.not_in_script","columns":null}]}""", 200, 0);
        ModelExtractor extractor = new ModelExtractor(url, 2000);
        ScriptExtraction ex = extractor.extract(src("print('hello')"));
        assertThat(ex.writes()).isEmpty();
        assertThat(ex.hints()).anyMatch(h ->
                h.kind() == ScriptExtraction.HintKind.PARSE_FAIL
                        && h.snippet().contains("dw.not_in_script"));
    }

    @Test
    void timeoutDegradesToEmptyWithoutThrowing() throws Exception {
        String url = startSidecar("{}", 200, 5_000);
        ModelExtractor extractor = new ModelExtractor(url, 300);
        long t0 = System.currentTimeMillis();
        ScriptExtraction ex = extractor.extract(src("x = 1"));
        assertThat(System.currentTimeMillis() - t0).isLessThan(3_000);
        assertThat(ex.reads()).isEmpty();
        assertThat(ex.writes()).isEmpty();
    }

    @Test
    void serverErrorDegradesToEmpty() throws Exception {
        String url = startSidecar("boom", 500, 0);
        ModelExtractor extractor = new ModelExtractor(url, 2000);
        ScriptExtraction ex = extractor.extract(src("x = 1"));
        assertThat(ex.reads()).isEmpty();
        assertThat(ex.writes()).isEmpty();
    }

    @Test
    void unconfiguredEndpointBypasses() {
        ModelExtractor extractor = new ModelExtractor("", 2000);
        assertThat(extractor.supports("PYTHON")).isFalse();
    }

    @Test
    void unreachableEndpointBypassesViaHealth() {
        ModelExtractor extractor = new ModelExtractor("http://127.0.0.1:1", 500);
        assertThat(extractor.supports("PYTHON")).isFalse();
    }

    @Test
    void nonScriptTypesUnsupported() throws Exception {
        String url = startSidecar("{}", 200, 0);
        ModelExtractor extractor = new ModelExtractor(url, 2000);
        assertThat(extractor.supports("SQL")).isFalse();
        assertThat(extractor.supports(null)).isFalse();
    }
}
