package com.dataweave.master.application.lineage.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.dataweave.master.application.lineage.ColumnEdge;
import com.dataweave.master.application.lineage.Confidence;
import com.dataweave.master.application.lineage.TableRef;
import com.dataweave.master.application.lineage.Transform;
import com.dataweave.master.application.lineage.script.ScriptExtraction;
import com.dataweave.master.application.lineage.script.ScriptLineageExtractor;
import com.dataweave.master.application.lineage.script.ScriptSource;
import com.dataweave.master.domain.lineage.LineageAgentConfig;
import com.dataweave.master.domain.lineage.Source;

/**
 * 053 云 AI Agent 抽取器（契约 agent-lineage-extractor C1/C2）。
 *
 * <p><b>不</b>加 {@code @Component}、不注册进 {@code ScriptLineageService} 同步 {@code extractors} 列表
 * （否则会拖慢 push，FR-004b）；由 {@code LineageAgentEnricher} 在异步流程内直接持有调用。
 *
 * <p>{@link #extract} 绝不外抛：超时/鉴权失败/非法结构/防幻觉拒收 → 空 {@link ScriptExtraction} + hint 留痕（FR-006）。
 */
public class AgentLineageExtractor implements ScriptLineageExtractor {

    private final LlmAgentClient client;
    private final AgentLineageConfigService configService;

    public AgentLineageExtractor(LlmAgentClient client, AgentLineageConfigService configService) {
        this.client = client;
        this.configService = configService;
    }

    /** 脚本任务类型判定（同步列表不收集本类，此方法仅供 enricher 复用判类型）。 */
    @Override
    public boolean supports(String taskType) {
        return isScript(taskType);
    }

    /** 是否应触发 AI 富化（D7）：脚本任务，或 Calcite 解析失败的 SQL。enabled 由 enricher 判配置。 */
    public boolean shouldEnrich(String taskType, boolean calciteParsed) {
        return isScript(taskType) || ("SQL".equalsIgnoreCase(taskType) && !calciteParsed);
    }

    private static boolean isScript(String taskType) {
        if (taskType == null) return false;
        String t = taskType.toUpperCase(Locale.ROOT);
        return "PYTHON".equals(t) || "SHELL".equals(t) || "SPARK".equals(t);
    }

    @Override
    public ScriptExtraction extract(ScriptSource source) {
        return extract(source, Collections.emptyMap());
    }

    /**
     * 含 schema 接地的抽取（US3/FR-016/T029）。
     *
     * @param source       脚本来源（tenant/project/taskDefId/type/content）
     * @param tableColumns 表名 → 该表真实列名集合（大小写不敏感），用于防幻觉列级校验 + 注入提示；
     *                     为空时退化为无 schema 标准流程
     * @return 抽取结果；越界列被拒收留痕
     */
    public ScriptExtraction extract(ScriptSource source, Map<String, Set<String>> tableColumns) {
        // enabled/配置兜底（enricher 已判，extract 自身防误调）
        LineageAgentConfig cfg = configService.getActive(source.tenantId(), source.projectId())
                .filter(LineageAgentConfig::enabled).orElse(null);
        if (cfg == null) return ScriptExtraction.empty(Source.SCRIPT_AGENT);

        boolean hasSchema = tableColumns != null && !tableColumns.isEmpty();
        LlmAgentClient.CallResult result;
        if (hasSchema) {
            Map<String, List<String>> promptColumns = tableColumns.entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, e -> List.copyOf(e.getValue())));
            result = client.extract(cfg, source.content(), source.taskType(), promptColumns);
        } else {
            result = client.extract(cfg, source.content(), source.taskType());
        }
        if (result.error() != null) {
            return degraded(result.error());
        }
        return applyAntiHallucination(result.extraction(), source.content(), cfg.model(), tableColumns);
    }

    /**
     * 防幻觉校验（C2/FR-005）：表名必须能在脚本文本字面定位（大小写不敏感，限定名或裸名任一命中）；
     * 越界表名/列边拒收并留痕。列边额外要求四要素齐全。
     * US3/FR-016：当 tableColumns 非空时，列级校验——字段边列名必须落在真实列集合内，越界拒收留痕。
     */
    private ScriptExtraction applyAntiHallucination(AgentExtraction ex, String content, String modelVersion) {
        return applyAntiHallucination(ex, content, modelVersion, Collections.emptyMap());
    }

    private ScriptExtraction applyAntiHallucination(AgentExtraction ex, String content, String modelVersion,
                                                     Map<String, Set<String>> tableColumns) {
        String lower = content == null ? "" : content.toLowerCase(Locale.ROOT);
        Set<String> reads = new LinkedHashSet<>();
        Set<String> writes = new LinkedHashSet<>();
        List<ColumnEdge> columnEdges = new ArrayList<>();
        List<ScriptExtraction.Hint> hints = new ArrayList<>();

        if (ex.reads() != null) {
            for (String t : ex.reads()) {
                if (locatable(t, lower)) reads.add(t.trim());
                else hints.add(reject("read table not in script", t));
            }
        }
        if (ex.writes() != null) {
            for (String t : ex.writes()) {
                if (locatable(t, lower)) writes.add(t.trim());
                else hints.add(reject("write table not in script", t));
            }
        }
        if (ex.columnEdges() != null) {
            for (AgentExtraction.ColumnEdge e : ex.columnEdges()) {
                // 基础校验：表名字面命中 + 列名非空
                if (!locatable(e.srcTable(), lower) || !locatable(e.dstTable(), lower)
                        || !present(e.srcColumn()) || !present(e.dstColumn())) {
                    hints.add(reject("column edge not locatable", e.srcTable() + "→" + e.dstTable()));
                    continue;
                }
                // US3/FR-016：列级 schema 接地校验——当已知表真实列清单时，列名必须落集合内
                if (!tableColumns.isEmpty()) {
                    if (!columnInRealSet(e.srcTable(), e.srcColumn(), tableColumns)) {
                        hints.add(reject("src column not in real schema",
                                e.srcTable() + "." + e.srcColumn()));
                        continue;
                    }
                    if (!columnInRealSet(e.dstTable(), e.dstColumn(), tableColumns)) {
                        hints.add(reject("dst column not in real schema",
                                e.dstTable() + "." + e.dstColumn()));
                        continue;
                    }
                }
                columnEdges.add(new ColumnEdge(
                        TableRef.of(e.srcTable()), e.srcColumn(),
                        TableRef.of(e.dstTable()), e.dstColumn(),
                        Transform.DIRECT, Confidence.UNVERIFIED));
            }
        }
        return new ScriptExtraction(reads, writes, columnEdges, hints, Source.SCRIPT_AGENT, modelVersion);
    }

    private static boolean locatable(String table, String lowerContent) {
        return table != null && !table.isBlank()
                && lowerContent.contains(table.trim().toLowerCase(Locale.ROOT));
    }

    private static boolean present(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * US3/FR-016：校验列名是否落在表的真实列集合内（大小写不敏感）。
     * 当 tableColumns 不含该表条目时放行（未解析到 schema 的表不阻）。
     */
    private static boolean columnInRealSet(String tableName, String columnName,
                                            Map<String, Set<String>> tableColumns) {
        if (tableName == null || columnName == null) return false;
        // 尝试多种 key 匹配：原始名、小写、大写（DatabaseMetaData 返回列名因方言大小写各异）
        Set<String> realCols = tableColumns.get(tableName);
        if (realCols == null) {
            realCols = tableColumns.get(tableName.toLowerCase(Locale.ROOT));
        }
        if (realCols == null) {
            realCols = tableColumns.get(tableName.toUpperCase(Locale.ROOT));
        }
        if (realCols == null) {
            return true; // 该表无 schema 记录 → 放行（不阻，宁少拒不多阻）
        }
        String col = columnName.toLowerCase(Locale.ROOT);
        return realCols.stream().anyMatch(c -> c != null && c.toLowerCase(Locale.ROOT).equals(col));
    }

    private static ScriptExtraction.Hint reject(String reason, String value) {
        String snippet = value == null ? "" : (value.length() > 60 ? value.substring(0, 60) : value);
        return new ScriptExtraction.Hint(ScriptExtraction.HintKind.PARSE_FAIL, 0,
                "agent output rejected (" + reason + "): " + snippet);
    }

    private static ScriptExtraction degraded(String error) {
        String snippet = error != null && error.length() > 120 ? error.substring(0, 120) : error;
        return new ScriptExtraction(Set.of(), Set.of(), List.of(),
                List.of(new ScriptExtraction.Hint(ScriptExtraction.HintKind.TIMEOUT, 0,
                        "agent call degraded: " + snippet)),
                Source.SCRIPT_AGENT, null);
    }
}
