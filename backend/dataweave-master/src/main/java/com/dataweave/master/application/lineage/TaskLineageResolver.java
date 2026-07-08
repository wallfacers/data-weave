package com.dataweave.master.application.lineage;

import java.util.ArrayList;
import java.util.List;

import com.dataweave.master.application.DatasourceSchemaResolver;
import com.dataweave.master.application.SqlColumnLineageExtractor;
import com.dataweave.master.application.lineage.script.ScriptLineageService;
import com.dataweave.master.application.lineage.script.ScriptSource;
import com.dataweave.master.domain.DatasourceRepository;
import com.dataweave.master.domain.lineage.ColumnEdge;
import com.dataweave.master.domain.lineage.IoEdge;
import com.dataweave.master.domain.lineage.Source;
import com.dataweave.master.infrastructure.lineage.Neo4jColumnBackfillWriter;
import org.springframework.stereotype.Service;

/**
 * 058 T010（方案 B）：任务血缘解析的<b>只读</b>共享核（宪法 V / research D3）。
 *
 * <p>把原先内联在 {@link com.dataweave.master.application.TaskService#recordLineage} 里的
 * 「Calcite 表级装配 + SQL 列级抽取 + 041 脚本通道」抽出为单一入口——<b>行为保持</b>：
 * push 侧仍在解析后自行 {@code recordTaskIo} + 发 AI 富化；authoring 侧只读取解析产物做接地，
 * 两侧共用同一抽取逻辑，杜绝「第二套抽取」漂移。本类<b>不落库、不触发 enrichment、不过写闸门</b>。
 */
@Service
public class TaskLineageResolver {

    private final LineageEdgeAssembler lineageEdgeAssembler;
    private final SqlColumnLineageExtractor sqlColumnLineageExtractor;
    private final ColumnLineageCatalog columnLineageCatalog;
    private final ScriptLineageService scriptLineageService;
    private final DatasourceSchemaResolver schemaResolver;
    private final Neo4jColumnBackfillWriter backfillWriter;
    private final DatasourceRepository datasourceRepository;
    private final SchemaCacheConfig schemaCacheConfig;

    public TaskLineageResolver(LineageEdgeAssembler lineageEdgeAssembler,
                               SqlColumnLineageExtractor sqlColumnLineageExtractor,
                               ColumnLineageCatalog columnLineageCatalog,
                               ScriptLineageService scriptLineageService,
                               DatasourceSchemaResolver schemaResolver,
                               Neo4jColumnBackfillWriter backfillWriter,
                               DatasourceRepository datasourceRepository,
                               SchemaCacheConfig schemaCacheConfig) {
        this.lineageEdgeAssembler = lineageEdgeAssembler;
        this.sqlColumnLineageExtractor = sqlColumnLineageExtractor;
        this.columnLineageCatalog = columnLineageCatalog;
        this.scriptLineageService = scriptLineageService;
        this.schemaResolver = schemaResolver;
        this.backfillWriter = backfillWriter;
        this.datasourceRepository = datasourceRepository;
        this.schemaCacheConfig = schemaCacheConfig;
    }

    /**
     * 只读解析产物：表级 + 列级边，及是否有 Calcite 成功解析（供 push 侧决定 AI 富化）。
     *
     * @param ioEdges       表级读写边（Calcite 装配 + 脚本通道并集）
     * @param columnEdges   列级血缘边（SQL 列抽取 + 脚本通道并集）
     * @param calciteParsed 是否有 {@code SQL_PARSED} 来源边（Calcite 成功解析）
     */
    public record ResolvedLineage(List<IoEdge> ioEdges, List<ColumnEdge> columnEdges, boolean calciteParsed) {}

    /**
     * 行为保持地解析一个任务/草稿的表级+列级血缘（与 push 时逐字等价，只是不落库）。
     * 与 {@code TaskService.recordLineage} 内联块严格一致：SQL 走 Calcite 列抽取，
     * PYTHON/SHELL(/SPARK) 并联脚本通道。
     */
    public ResolvedLineage resolve(long tenantId, long projectId, Long taskDefId,
                                   String type, String content,
                                   Long datasourceId, Long targetDatasourceId,
                                   List<String> agentReads, List<String> agentWrites) {
        LineageEdgeAssembler.Assembly assembly = lineageEdgeAssembler.assemble(
                tenantId, projectId, type, content, agentReads, agentWrites,
                datasourceId, targetDatasourceId);

        List<ColumnEdge> columnEdges = List.of();
        if ("SQL".equalsIgnoreCase(type)) {
            var catalog = catalogFor(datasourceId);
            var colResult = sqlColumnLineageExtractor.extractAndCrossCheck(
                    content, catalog, List.of(), tenantId, projectId);
            columnEdges = ColumnLineageStoreAdapter.toDomain(
                    colResult,
                    lineageEdgeAssembler.resolveCoord(tenantId, projectId, datasourceId),
                    lineageEdgeAssembler.resolveCoord(tenantId, projectId, targetDatasourceId));
        }

        var ioEdges = new ArrayList<>(assembly.ioEdges());
        var allColumnEdges = new ArrayList<>(columnEdges);
        if (scriptLineageService.handles(type)) {
            var scriptResult = scriptLineageService.extract(
                    new ScriptSource(tenantId, projectId, taskDefId, type, content, datasourceId, targetDatasourceId));
            ioEdges.addAll(scriptResult.ioEdges());
            allColumnEdges.addAll(scriptResult.columnEdges());
        }

        boolean calciteParsed = assembly.ioEdges().stream()
                .anyMatch(e -> e.source() == Source.SQL_PARSED);
        return new ResolvedLineage(ioEdges, allColumnEdges, calciteParsed);
    }

    /** 构造绑定指定数据源的组合 catalog（供列抽取 + authoring 三态存在性接地探针复用）。 */
    public DatasourceBoundCatalog catalogFor(Long datasourceId) {
        return new DatasourceBoundCatalog(
                datasourceId, columnLineageCatalog, schemaResolver, backfillWriter, datasourceRepository,
                schemaCacheConfig.ttlMs());
    }
}
