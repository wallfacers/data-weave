package com.dataweave.master.application.lineage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.dataweave.master.application.SqlTableExtractor;
import com.dataweave.master.domain.lineage.Confidence;
import com.dataweave.master.domain.lineage.DatasourceCoord;
import com.dataweave.master.domain.lineage.Direction;
import com.dataweave.master.domain.lineage.IoEdge;
import com.dataweave.master.domain.lineage.Source;
import com.dataweave.master.domain.lineage.TableRef;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 任务 content → {@link IoEdge} 装配器（A×B 交叉校验）。
 *
 * <p>抽取自原 {@code TaskService.recordLineage/buildEdges}，供 {@code createAndOnline}（{@link
 * com.dataweave.master.application.TaskService}）与 {@code push}（{@link
 * com.dataweave.master.application.ProjectSyncService}）两条创作路径复用，保证血缘语义一致（US1 + US2）。
 *
 * <p>A×B 矩阵（Agent 声明 × SQL 解析，表名比对大小写不敏感）：
 * <ul>
 *   <li>两者皆有 → {@link Source#AGENT} / {@link Confidence#CONFIRMED}</li>
 *   <li>仅声明且解析成功（表名不匹配）→ AGENT / {@link Confidence#CONFLICT}</li>
 *   <li>仅声明（解析失败/未解析）→ AGENT / {@link Confidence#UNVERIFIED}</li>
 *   <li>仅解析 → {@link Source#SQL_PARSED} / CONFIRMED</li>
 * </ul>
 * 呈现保留 Agent 声明优先的原始拼写。
 *
 * <p>{@link #resolveCoord} 把 PG {@code datasource_id} 解析为去重身份 {@link DatasourceCoord}（FR-004）：
 * 查 {@code datasources}(host/port/database_name/name) + {@code datasource_types.default_port} 补缺省端口；
 * 缺连接坐标走降级身份。失败/缺 id 不抛异常（血缘是增强，FR-007）。
 */
@Component
public class LineageEdgeAssembler {

    private static final String DS_SQL =
            "SELECT d.host, d.port, d.database_name, d.name, dt.default_port " +
            "FROM datasources d LEFT JOIN datasource_types dt ON d.type_code = dt.code " +
            "WHERE d.id = ? AND d.deleted = 0";

    private final SqlTableExtractor sqlTableExtractor;
    private final JdbcTemplate jdbcTemplate;

    public LineageEdgeAssembler(SqlTableExtractor sqlTableExtractor, JdbcTemplate jdbcTemplate) {
        this.sqlTableExtractor = sqlTableExtractor;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 装配结果：表级 IoEdge 列表（列级 ColumnEdge 由 019 产出，不在本装配器）。 */
    public record Assembly(List<IoEdge> ioEdges) {}

    /**
     * 解析任务 content + 合并 Agent 声明，产出表级 {@link IoEdge} 列表（A×B 交叉校验）。
     *
     * @param tenantId       租户（coord 身份 + 隔离）
     * @param projectId      项目
     * @param type           任务类型（仅 "SQL" 触发 Calcite 解析，否则视为未解析）
     * @param content        任务内容（SQL 文本）
     * @param agentReads     Agent 声明的读表
     * @param agentWrites    Agent 声明的写表
     * @param readDatasourceId  读侧数据源 id（PG；可 null）
     * @param writeDatasourceId 写侧目标数据源 id（PG；可 null）
     */
    public Assembly assemble(long tenantId, long projectId, String type, String content,
                             List<String> agentReads, List<String> agentWrites,
                             Long readDatasourceId, Long writeDatasourceId) {
        SqlTableExtractor.Result parsed = "SQL".equalsIgnoreCase(type)
                ? sqlTableExtractor.extract(content)
                : new SqlTableExtractor.Result(false, Set.of(), Set.of());
        DatasourceCoord readCoord = resolveCoord(tenantId, projectId, readDatasourceId);
        DatasourceCoord writeCoord = resolveCoord(tenantId, projectId, writeDatasourceId);
        List<IoEdge> edges = new ArrayList<>();
        edges.addAll(buildEdges(readCoord, Direction.READS, agentReads, parsed.reads(), parsed.parsed()));
        edges.addAll(buildEdges(writeCoord, Direction.WRITES, agentWrites, parsed.writes(), parsed.parsed()));
        return new Assembly(edges);
    }

    /** 把 PG datasource_id 解析为去重身份 DatasourceCoord（T016）。 */
    public DatasourceCoord resolveCoord(long tenantId, long projectId, Long datasourceId) {
        if (datasourceId == null) {
            return new DatasourceCoord(tenantId, projectId, null, null, null, null);
        }
        try {
            return jdbcTemplate.queryForObject(DS_SQL, (rs, n) -> {
                Integer port = rs.getObject("port", Integer.class);
                Integer defaultPort = rs.getObject("default_port", Integer.class);
                Integer resolvedPort = port != null ? port : defaultPort;
                return new DatasourceCoord(tenantId, projectId,
                        rs.getString("host"), resolvedPort,
                        rs.getString("database_name"), rs.getString("name"));
            }, datasourceId);
        } catch (EmptyResultDataAccessException e) {
            // 数据源行不存在：降级身份（fallbackName = id），仍唯一不重复
            return new DatasourceCoord(tenantId, projectId, null, null, null, String.valueOf(datasourceId));
        }
    }

    /** 构造 TableRef（复用 inferLayer；供 runtime recordSynced 写表引用，feature 025）。 */
    public TableRef tableRef(DatasourceCoord coord, String qualifiedName) {
        return new TableRef(coord, qualifiedName, inferLayer(qualifiedName));
    }

    private List<IoEdge> buildEdges(DatasourceCoord coord, Direction direction, List<String> agent,
                                    Set<String> parsedSet, boolean parsed) {
        // 规范化映射：lower → 原始拼写（声明优先）
        Map<String, String> canonical = new LinkedHashMap<>();
        Set<String> agentLower = new LinkedHashSet<>();
        if (agent != null) {
            for (String a : agent) {
                if (a == null || a.isBlank()) {
                    continue;
                }
                String t = a.trim();
                canonical.put(t.toLowerCase(), t);
                agentLower.add(t.toLowerCase());
            }
        }
        Set<String> parsedLower = new LinkedHashSet<>();
        for (String p : parsedSet) {
            String low = p.toLowerCase();
            parsedLower.add(low);
            canonical.putIfAbsent(low, p);
        }

        List<IoEdge> out = new ArrayList<>();
        for (String low : canonical.keySet()) {
            boolean inAgent = agentLower.contains(low);
            boolean inParsed = parsedLower.contains(low);
            Source source;
            Confidence confidence;
            if (inAgent && inParsed) {
                source = Source.AGENT;
                confidence = Confidence.CONFIRMED;
            } else if (inAgent && parsed) {
                source = Source.AGENT;
                confidence = Confidence.CONFLICT;
            } else if (inAgent) {
                source = Source.AGENT;
                confidence = Confidence.UNVERIFIED;
            } else {
                source = Source.SQL_PARSED;
                confidence = Confidence.CONFIRMED;
            }
            String qn = canonical.get(low);
            out.add(new IoEdge(new TableRef(coord, qn, inferLayer(qn)), direction, source, confidence));
        }
        return out;
    }

    /** 命名前缀推导分层（ods_/dwd_/dws_/ads_，大小写不敏感）；不匹配返回 null。 */
    private static String inferLayer(String qualifiedName) {
        if (qualifiedName == null) {
            return null;
        }
        String t = qualifiedName.toLowerCase();
        int dot = t.lastIndexOf('.');
        if (dot >= 0) {
            t = t.substring(dot + 1);
        }
        if (t.startsWith("ods_") || t.equals("ods")) {
            return "ODS";
        }
        if (t.startsWith("dwd_")) {
            return "DWD";
        }
        if (t.startsWith("dws_")) {
            return "DWS";
        }
        if (t.startsWith("ads_")) {
            return "ADS";
        }
        return null;
    }
}
