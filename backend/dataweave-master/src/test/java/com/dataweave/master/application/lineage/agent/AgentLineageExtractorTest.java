package com.dataweave.master.application.lineage.agent;

import java.util.List;
import java.util.Optional;

import com.dataweave.master.application.DatasourceEncryptor;
import com.dataweave.master.application.lineage.script.ScriptExtraction;
import com.dataweave.master.application.lineage.script.ScriptSource;
import com.dataweave.master.domain.lineage.LineageAgentConfig;
import com.dataweave.master.domain.lineage.Source;
import com.dataweave.master.infrastructure.lineage.AgentConfigRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 053 AgentLineageExtractor 防幻觉单测（T017，契约 C2 / FR-005）。
 * 表名必须能在脚本中字面定位，否则拒收并留痕；client 错误/未启用 → 空产物绝不外抛。
 */
class AgentLineageExtractorTest {

    private static final String SCRIPT = "INSERT INTO dw.real SELECT * FROM user";
    private static final LineageAgentConfig CFG = new LineageAgentConfig(
            1L, 1L, "OPENAI", "https://x", "m", "enc", true, false, 30000, 60, 2000, null, null, null, null, 0, 0);

    @Test
    void rejectsTableNotLocatableInScript() {
        AgentLineageExtractor ext = new AgentLineageExtractor(
                clientReturning(new AgentExtraction(List.of("phantom"), List.of("dw.real"), List.of(), 0.9, "m")),
                configEnabled());
        ScriptExtraction r = ext.extract(src());

        assertThat(r.channel()).isEqualTo(Source.SCRIPT_AGENT);
        assertThat(r.writes()).contains("dw.real");          // 脚本中存在 → 接受
        assertThat(r.reads()).doesNotContain("phantom");     // 脚本中不存在 → 拒收
        assertThat(r.hints()).anyMatch(h -> h.kind() == ScriptExtraction.HintKind.PARSE_FAIL);
    }

    @Test
    void acceptsLocatableTables() {
        AgentLineageExtractor ext = new AgentLineageExtractor(
                clientReturning(new AgentExtraction(List.of("user"), List.of("dw.real"), List.of(), 0.9, "m")),
                configEnabled());
        ScriptExtraction r = ext.extract(src());
        assertThat(r.reads()).containsExactly("user");
        assertThat(r.writes()).containsExactly("dw.real");
        assertThat(r.hints()).isEmpty();
        assertThat(r.modelVersion()).isEqualTo("m");
    }

    @Test
    void acceptsColumnEdgeWhenTablesLocatable() {
        AgentExtraction.ColumnEdge edge = new AgentExtraction.ColumnEdge("user", "id", "dw.real", "id");
        AgentLineageExtractor ext = new AgentLineageExtractor(
                clientReturning(new AgentExtraction(List.of("user"), List.of("dw.real"), List.of(edge), 0.9, "m")),
                configEnabled());
        ScriptExtraction r = ext.extract(src());
        assertThat(r.columnEdges()).hasSize(1);
    }

    @Test
    void rejectsColumnEdgeWhenTableNotInScript() {
        AgentExtraction.ColumnEdge edge = new AgentExtraction.ColumnEdge("ghost", "id", "dw.real", "id");
        AgentLineageExtractor ext = new AgentLineageExtractor(
                clientReturning(new AgentExtraction(List.of(), List.of("dw.real"), List.of(edge), 0.9, "m")),
                configEnabled());
        ScriptExtraction r = ext.extract(src());
        assertThat(r.columnEdges()).isEmpty();
        assertThat(r.hints()).anyMatch(h -> h.kind() == ScriptExtraction.HintKind.PARSE_FAIL);
    }

    @Test
    void rejectsSubstringOnlyTableMatch() {
        // P4：脚本仅含复数表 orders，模型幻觉出单数 order —— 旧子串匹配会误命中 orders 而放行；词边界须拒收
        AgentLineageExtractor ext = new AgentLineageExtractor(
                clientReturning(new AgentExtraction(List.of("order"), List.of("dw.real"), List.of(), 0.9, "m")),
                configEnabled());
        ScriptExtraction r = ext.extract(srcWith("INSERT INTO dw.real SELECT * FROM orders"));
        assertThat(r.reads()).doesNotContain("order");
        assertThat(r.hints()).anyMatch(h -> h.kind() == ScriptExtraction.HintKind.PARSE_FAIL);
    }

    @Test
    void acceptsQualifiedNameByBareSegment() {
        // P4：模型返回限定名 app.user，脚本仅含裸名 user —— 末段裸名命中即接受（限定名或裸名任一命中）
        AgentLineageExtractor ext = new AgentLineageExtractor(
                clientReturning(new AgentExtraction(List.of("app.user"), List.of("dw.real"), List.of(), 0.9, "m")),
                configEnabled());
        ScriptExtraction r = ext.extract(src());
        assertThat(r.reads()).containsExactly("app.user");
        assertThat(r.hints()).isEmpty();
    }

    @Test
    void degradesToEmptyWhenClientErrors() {
        AgentLineageExtractor ext = new AgentLineageExtractor(
                clientWithError("timeout"), configEnabled());
        ScriptExtraction r = ext.extract(src());
        assertThat(r.reads()).isEmpty();
        assertThat(r.writes()).isEmpty();
        assertThat(r.hints()).anyMatch(h -> h.kind() == ScriptExtraction.HintKind.TIMEOUT);
    }

    @Test
    void bypassesWhenConfigDisabled() {
        AgentLineageExtractor ext = new AgentLineageExtractor(
                clientReturning(new AgentExtraction(List.of("user"), List.of(), List.of(), 0.9, "m")),
                configDisabled());
        ScriptExtraction r = ext.extract(src());
        assertThat(r.reads()).isEmpty();   // 未启用 → 旁路，零外呼（FR-019）
        assertThat(r.channel()).isEqualTo(Source.SCRIPT_AGENT);
    }

    // ── US3/T029：列级 schema 接地校验 ──────────────────────────────────

    @Test
    void columnEdgeAcceptedWhenColumnsInRealSchema() {
        AgentExtraction.ColumnEdge edge = new AgentExtraction.ColumnEdge("user", "id", "dw.real", "id");
        AgentLineageExtractor ext = new AgentLineageExtractor(
                clientReturning(new AgentExtraction(List.of("user"), List.of("dw.real"), List.of(edge), 0.9, "m")),
                configEnabled());
        // 提供真实列清单：user 表有 id/name/email，dw.real 表有 id/val
        var schemaCtx = java.util.Map.of(
                "user", java.util.Set.of("id", "name", "email"),
                "dw.real", java.util.Set.of("id", "val"));
        ScriptExtraction r = ext.extract(src(), schemaCtx);
        assertThat(r.columnEdges()).hasSize(1);
        assertThat(r.hints()).isEmpty();
    }

    @Test
    void columnEdgeRejectedWhenSrcColumnNotInRealSchema() {
        AgentExtraction.ColumnEdge edge = new AgentExtraction.ColumnEdge("user", "phantom_col", "dw.real", "id");
        AgentLineageExtractor ext = new AgentLineageExtractor(
                clientReturning(new AgentExtraction(List.of("user"), List.of("dw.real"), List.of(edge), 0.9, "m")),
                configEnabled());
        var schemaCtx = java.util.Map.of(
                "user", java.util.Set.of("id", "name"),
                "dw.real", java.util.Set.of("id", "val"));
        ScriptExtraction r = ext.extract(src(), schemaCtx);
        assertThat(r.columnEdges()).isEmpty();
        assertThat(r.hints()).anyMatch(h -> h.kind() == ScriptExtraction.HintKind.PARSE_FAIL
                && h.snippet().contains("src column not in real schema"));
    }

    @Test
    void columnEdgeRejectedWhenDstColumnNotInRealSchema() {
        AgentExtraction.ColumnEdge edge = new AgentExtraction.ColumnEdge("user", "id", "dw.real", "ghost_col");
        AgentLineageExtractor ext = new AgentLineageExtractor(
                clientReturning(new AgentExtraction(List.of("user"), List.of("dw.real"), List.of(edge), 0.9, "m")),
                configEnabled());
        var schemaCtx = java.util.Map.of(
                "user", java.util.Set.of("id", "name"),
                "dw.real", java.util.Set.of("id", "val"));
        ScriptExtraction r = ext.extract(src(), schemaCtx);
        assertThat(r.columnEdges()).isEmpty();
        assertThat(r.hints()).anyMatch(h -> h.snippet().contains("dst column not in real schema"));
    }

    @Test
    void columnEdgePassedWhenTableNotInSchemaContext() {
        // user 表在脚本中但不在 schema context 中 → 列级校验放行（宁少拒不多阻）
        AgentExtraction.ColumnEdge edge = new AgentExtraction.ColumnEdge("user", "id", "dw.real", "id");
        AgentLineageExtractor ext = new AgentLineageExtractor(
                clientReturning(new AgentExtraction(List.of("user"), List.of("dw.real"), List.of(edge), 0.9, "m")),
                configEnabled());
        // 仅提供 dw.real 的 schema，user 不在 schema context 中
        var schemaCtx = java.util.Map.of("dw.real", java.util.Set.of("id", "val"));
        ScriptExtraction r = ext.extract(src(), schemaCtx);
        // user 表无 schema 记录 → columnInRealSet 返回 true（放行）
        assertThat(r.columnEdges()).hasSize(1);
    }

    @Test
    void emptySchemaContextDoesNotAffectExistingValidation() {
        AgentExtraction.ColumnEdge edge = new AgentExtraction.ColumnEdge("user", "id", "dw.real", "id");
        AgentLineageExtractor ext = new AgentLineageExtractor(
                clientReturning(new AgentExtraction(List.of("user"), List.of("dw.real"), List.of(edge), 0.9, "m")),
                configEnabled());
        ScriptExtraction r = ext.extract(src(), java.util.Collections.emptyMap());
        assertThat(r.columnEdges()).hasSize(1);
    }

    @Test
    void shouldEnrichScriptsAndCalciteFailedSqlOnly() {
        AgentLineageExtractor ext = new AgentLineageExtractor(clientReturning(AgentExtraction.empty("m")), configEnabled());
        assertThat(ext.shouldEnrich("PYTHON", false)).isTrue();
        assertThat(ext.shouldEnrich("SPARK", true)).isTrue();
        assertThat(ext.shouldEnrich("SQL", false)).isTrue();   // Calcite 失败 → AI 兜底（D7）
        assertThat(ext.shouldEnrich("SQL", true)).isFalse();   // Calcite 成功 → 不外呼（D7）
    }

    private static ScriptSource src() {
        return new ScriptSource(1L, 1L, 1L, "PYTHON", SCRIPT, null, null);
    }

    private static ScriptSource srcWith(String content) {
        return new ScriptSource(1L, 1L, 1L, "PYTHON", content, null, null);
    }

    private static LlmAgentClient clientReturning(AgentExtraction ex) {
        return new LlmAgentClient(List.of(), null) {
            @Override
            public CallResult extract(com.dataweave.master.domain.lineage.LineageAgentConfig c, String content, String type) {
                return new CallResult(ex, 50, null);
            }
            @Override
            public CallResult extract(com.dataweave.master.domain.lineage.LineageAgentConfig c, String content,
                                       String type, java.util.Map<String, java.util.List<String>> tableColumns) {
                return new CallResult(ex, 50, null);
            }
        };
    }

    private static LlmAgentClient clientWithError(String error) {
        return new LlmAgentClient(List.of(), null) {
            @Override
            public CallResult extract(com.dataweave.master.domain.lineage.LineageAgentConfig c, String content, String type) {
                return new CallResult(AgentExtraction.empty(c.model()), 50, error);
            }
            @Override
            public CallResult extract(com.dataweave.master.domain.lineage.LineageAgentConfig c, String content,
                                       String type, java.util.Map<String, java.util.List<String>> tableColumns) {
                return new CallResult(AgentExtraction.empty(c.model()), 50, error);
            }
        };
    }

    private static AgentLineageConfigService configEnabled() {
        return new AgentLineageConfigService(null, null) {
            @Override
            public Optional<LineageAgentConfig> getActive(long tenantId) {
                return Optional.of(CFG);
            }
        };
    }

    private static AgentLineageConfigService configDisabled() {
        return new AgentLineageConfigService(null, null) {
            @Override
            public Optional<LineageAgentConfig> getActive(long tenantId) {
                return Optional.empty();
            }
        };
    }
}
