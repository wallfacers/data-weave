package com.dataweave.master.infrastructure.lineage;

import com.dataweave.master.application.lineage.ColumnMeta;
import com.dataweave.master.domain.lineage.DatasourceCoord;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Neo4j 列元数据回填器（FR-017 回填列目录缓存）。
 *
 * <p>将 {@link com.dataweave.master.application.DatasourceSchemaResolver} 实时抓取到的列清单
 * 写入 neo4j，使 {@link Neo4jColumnLineageCatalog} 后续查询直接命中、不再重复连库。
 *
 * <p>复用 {@link Neo4jLineageStore} 相同的节点键合成规则（dsKey / tableKey / columnKey）
 * 与 {@code MERGE} 幂等语义——重复回填安全，结构变更时覆盖刷新 {@code dataType/ordinal}。
 *
 * <p><b>安全边界</b>：仅写元数据节点与结构边（:Datasource / :Table / :Column / HAS_TABLE / HAS_COLUMN），
 * 不写任务边（READS/WRITES/DERIVES_FROM），不触碰既有血缘。
 */
@Component
public class Neo4jColumnBackfillWriter {

    private static final Logger log = LoggerFactory.getLogger(Neo4jColumnBackfillWriter.class);

    private final Driver driver;

    public Neo4jColumnBackfillWriter(Driver driver) {
        this.driver = driver;
    }

    /**
     * 回填一张表的所有列元数据到 neo4j（幂等 MERGE）。
     *
     * @param coord         数据源坐标（用于合成 dsKey / tableKey）
     * @param qualifiedName 表限定名（如 "public.user"）
     * @param columns       列清单（含 name / dataType / ordinal）
     * @param tenantId      租户 ID
     * @param projectId     项目 ID
     */
    public void backfillColumns(DatasourceCoord coord, String qualifiedName,
                                List<ColumnMeta> columns, long tenantId, long projectId) {
        if (coord == null || qualifiedName == null || qualifiedName.isBlank()
                || columns == null || columns.isEmpty()) {
            return;
        }
        String dsKey = coord.dsKey();
        String tableKey = tableKey(dsKey, qualifiedName);

        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                // 1. MERGE :Datasource（幂等）
                tx.run("""
                        MERGE (d:Datasource {dsKey:$dk})
                          ON CREATE SET d.id=$dk, d.tenantId=$t, d.projectId=$p,
                                        d.ip=$ip, d.port=$port, d.database=$db, d.name=$nm
                        """, params("dk", dsKey, "t", tenantId, "p", projectId,
                        "ip", coord.ip(), "port", coord.port(),
                        "db", coord.database(),
                        "nm", coord.fallbackName() != null ? coord.fallbackName() : coord.database()))
                        .consume();

                // 2. MERGE :Table（幂等）
                tx.run("""
                        MERGE (t:Table {tableKey:$tk})
                          ON CREATE SET t.id=$tk, t.datasourceId=$dk, t.tenantId=$t, t.projectId=$p,
                                        t.qualifiedName=$qn
                        """, params("tk", tableKey, "dk", dsKey, "t", tenantId, "p", projectId,
                        "qn", qualifiedName)).consume();

                // 3. HAS_TABLE 结构边
                tx.run("""
                        MATCH (d:Datasource {dsKey:$dk}),(t:Table {tableKey:$tk})
                        MERGE (d)-[:HAS_TABLE]->(t)
                        """, params("dk", dsKey, "tk", tableKey)).consume();

                // 4. 逐列 MERGE :Column + HAS_COLUMN，回填 dataType/ordinal
                for (int i = 0; i < columns.size(); i++) {
                    ColumnMeta col = columns.get(i);
                    String columnKey = columnKey(tableKey, col.name());
                    int ordinal = col.ordinal() >= 0 ? col.ordinal() : i;
                    tx.run("""
                            MERGE (c:Column {columnKey:$ck})
                              ON CREATE SET c.id=$ck, c.tenantId=$t, c.projectId=$p, c.name=$nm,
                                            c.tableKey=$tk, c.dataType=$dt, c.ordinal=$ord
                              ON MATCH SET c.dataType=$dt, c.ordinal=$ord
                            """, params("ck", columnKey, "t", tenantId, "p", projectId,
                            "nm", col.name(), "tk", tableKey, "dt", col.dataType(), "ord", ordinal))
                            .consume();
                    tx.run("""
                            MATCH (t:Table {tableKey:$tk}),(c:Column {columnKey:$ck})
                            MERGE (t)-[:HAS_COLUMN]->(c)
                            """, params("tk", tableKey, "ck", columnKey)).consume();
                }
                return null;
            });
        }
        log.debug("Neo4jColumnBackfillWriter: backfilled {} columns for {} (tableKey={})",
                columns.size(), qualifiedName, tableKey);
    }

    // ── 键合成（与 Neo4jLineageStore 同源）─────────────────────────────

    static String tableKey(String dsKey, String qualifiedName) {
        return dsKey + "|" + norm(qualifiedName);
    }

    static String columnKey(String tableKey, String colName) {
        return tableKey + "|" + norm(colName);
    }

    static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase();
    }

    private static Map<String, Object> params(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
