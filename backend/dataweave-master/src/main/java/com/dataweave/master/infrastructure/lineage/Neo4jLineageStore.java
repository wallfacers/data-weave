package com.dataweave.master.infrastructure.lineage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.dataweave.master.domain.lineage.ColumnEdge;
import com.dataweave.master.domain.lineage.Confidence;
import com.dataweave.master.domain.lineage.DatasourceCoord;
import com.dataweave.master.domain.lineage.Direction;
import com.dataweave.master.domain.lineage.IoEdge;
import com.dataweave.master.domain.lineage.LineageStore;
import com.dataweave.master.domain.lineage.MetricEdge;
import com.dataweave.master.domain.lineage.TableRef;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.TransactionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link LineageStore} 的 neo4j 实现（infrastructure）。
 *
 * <p>写入语义（replace-per-task 单事务，{@code session.executeWrite} managed write tx，D4/SC-003）：
 * <ol>
 *   <li>MERGE :Task 镜像节点（taskKey = tenantId|task|taskDefId）。</li>
 *   <li>删本任务旧边：Task 的 READS/WRITES/READS_COL/WRITES_COL（按 taskKey）+ 带 taskDefId 的 FLOWS_TO/DERIVES_FROM（按边属性）。</li>
 *   <li>MERGE 共享节点：:Datasource(dsKey) / :Table(tableKey) / :Column(columnKey)，及 HAS_TABLE/HAS_COLUMN 结构边。</li>
 *   <li>CREATE 本任务新边：READS/WRITES（含 source/confidence/version）+ 派生 FLOWS_TO（READ 表 × WRITE 表，带 taskDefId）+ DERIVES_FROM（列级，带 taskDefId/transform）。</li>
 * </ol>
 * 节点 MERGE（upsert，共享、跨任务不删）；边 replace（按 taskDefId 私有，先删后建）——区分是幂等正确性关键。
 *
 * <p>不变量见 {@link LineageStore} javadoc。
 */
@Component
public class Neo4jLineageStore implements LineageStore {

    private static final Logger log = LoggerFactory.getLogger(Neo4jLineageStore.class);

    private final Driver driver;

    public Neo4jLineageStore(Driver driver) {
        this.driver = driver;
    }

    @Override
    public void recordTaskIo(long tenantId, long projectId, long taskDefId,
                             Integer versionNo, String taskName,
                             List<IoEdge> ioEdges, List<ColumnEdge> columnEdges) {
        String taskKey = taskKey(tenantId, taskDefId);
        int v = versionNo == null ? 0 : versionNo;
        List<IoEdge> edges = ioEdges == null ? List.of() : ioEdges;
        List<ColumnEdge> cols = columnEdges == null ? List.of() : columnEdges;

        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                // 1. MERGE :Task 镜像（带 tenantId/projectId 隔离 + versionNo）
                tx.run("""
                        MERGE (n:Task {taskKey:$k})
                        SET n.id=$k, n.tenantId=$t, n.projectId=$p, n.taskDefId=$td, n.name=$nm, n.versionNo=$v
                        """, params("k", taskKey, "t", tenantId, "p", projectId,
                        "td", taskDefId, "nm", taskName, "v", v)).consume();

                // 2. 删本任务旧边（replace-per-task）
                tx.run("MATCH (:Task {taskKey:$k})-[r:READS|WRITES|READS_COL|WRITES_COL]->() DELETE r",
                        params("k", taskKey)).consume();
                tx.run("MATCH ()-[f:FLOWS_TO {taskDefId:$td}]->() DELETE f",
                        params("td", taskDefId)).consume();
                tx.run("MATCH ()-[d:DERIVES_FROM {taskDefId:$td}]->() DELETE d",
                        params("td", taskDefId)).consume();

                // 3+4. MERGE 表节点 + CREATE READS/WRITES（顺带建 datasource/HAS_TABLE）
                List<String> readTableKeys = new ArrayList<>();
                List<String> writeTableKeys = new ArrayList<>();
                for (IoEdge e : edges) {
                    String tk = ensureTable(tx, e.table(), tenantId, projectId);
                    if (e.direction() == Direction.READS) {
                        readTableKeys.add(tk);
                    } else {
                        writeTableKeys.add(tk);
                    }
                    String relType = e.direction() == Direction.READS ? "READS" : "WRITES";
                    tx.run("""
                            MATCH (n:Task {taskKey:$kk}),(t:Table {tableKey:$tk})
                            CREATE (n)-[:%s {source:$s,confidence:$c,version:$v,taskDefId:$td}]->(t)
                            """.formatted(relType),
                            params("kk", taskKey, "tk", tk, "s", nameOf(e.source()),
                                    "c", nameOf(e.confidence()), "v", v, "td", taskDefId)).consume();
                }

                // 派生 FLOWS_TO：READ 表 × WRITE 表（带 taskDefId；跳过自环）
                for (String rk : readTableKeys) {
                    for (String wk : writeTableKeys) {
                        if (rk.equals(wk)) {
                            continue;
                        }
                        tx.run("""
                                MATCH (a:Table {tableKey:$rk}),(b:Table {tableKey:$wk})
                                CREATE (a)-[:FLOWS_TO {taskDefId:$td}]->(b)
                                """, params("rk", rk, "wk", wk, "td", taskDefId)).consume();
                    }
                }

                // 5. 列级边：MERGE :Column + HAS_COLUMN + DERIVES_FROM（019 产出；本期能写，FR-011）
                for (ColumnEdge ce : cols) {
                    String srcTk = ensureTable(tx, ce.srcTable(), tenantId, projectId);
                    String dstTk = ensureTable(tx, ce.dstTable(), tenantId, projectId);
                    String srcCk = ensureColumn(tx, srcTk, ce.srcCol(), null, null, tenantId, projectId);
                    String dstCk = ensureColumn(tx, dstTk, ce.dstCol(), null, null, tenantId, projectId);
                    tx.run("""
                            MATCH (a:Column {columnKey:$sck}),(b:Column {columnKey:$dck})
                            CREATE (a)-[:DERIVES_FROM {taskDefId:$td,transform:$tr,confidence:$cf}]->(b)
                            """, params("sck", srcCk, "dck", dstCk, "td", taskDefId,
                            "tr", nameOf(ce.transform()), "cf", nameOf(ce.confidence()))).consume();
                }
                return null;
            });
        }
        log.debug("recordTaskIo: tenant={} project={} taskDef={} io={} col={}",
                tenantId, projectId, taskDefId, edges.size(), cols.size());
    }

    @Override
    public void recordMetricLineage(MetricEdge edge) {
        if (edge == null) {
            return;
        }
        String metricKey = edge.tenantId() + "|metric|" + edge.metricType() + "|" + edge.metricId();
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("""
                        MERGE (m:Metric {metricKey:$mk})
                          ON CREATE SET m.id=$mk, m.tenantId=$t, m.projectId=$p, m.metricType=$mt, m.metricId=$mid, m.name=$nm
                        """, params("mk", metricKey, "t", edge.tenantId(), "p", edge.projectId(),
                        "mt", edge.metricType(), "mid", edge.metricId(), "nm", edge.metricName())).consume();
                // 下游 TABLE：按 qualifiedName+tenant 匹配已存在表（任务路径建），不存在则建最小节点
                if ("TABLE".equalsIgnoreCase(edge.downstreamType()) && edge.downstreamRef() != null) {
                    tx.run("""
                            MATCH (m:Metric {metricKey:$mk})
                            MERGE (t:Table {qualifiedName:$qn, tenantId:$t})
                              ON CREATE SET t.projectId=$p
                            MERGE (m)-[:COMPUTED_FROM]->(t)
                            """, params("mk", metricKey, "qn", edge.downstreamRef(),
                            "t", edge.tenantId(), "p", edge.projectId())).consume();
                }
                // COLUMN 下游本期从简（列级在 019、查询在 020 完善）
                return null;
            });
        }
        log.debug("recordMetricLineage: metric={}|{}", edge.metricType(), edge.metricId());
    }

    @Override
    public void recordSynced(long tenantId, long projectId, String instanceId,
                             TableRef table, Long rowCount, Long bytes, String bizDate) {
        if (instanceId == null || table == null) {
            return;
        }
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("""
                        MERGE (r:TaskRun {instanceId:$iid})
                          ON CREATE SET r.id=$iid, r.tenantId=$t, r.projectId=$p
                        """, params("iid", instanceId, "t", tenantId, "p", projectId)).consume();
                String tk = ensureTable(tx, table, tenantId, projectId);
                tx.run("""
                        MATCH (r:TaskRun {instanceId:$iid}),(t:Table {tableKey:$tk})
                        MERGE (r)-[:SYNCED {rowCount:$rc,bytes:$b,bizDate:$bd}]->(t)
                        """, params("iid", instanceId, "tk", tk,
                        "rc", rowCount, "b", bytes, "bd", bizDate)).consume();
                return null;
            });
        }
        log.debug("recordSynced: instance={} table={}", instanceId, table.qualifiedName());
    }

    // ── 内部 Cypher 装配 helper ───────────────────────────────────────────────

    /** MERGE datasource + table + HAS_TABLE，返回 tableKey（幂等，重复 MERGE 安全）。 */
    private String ensureTable(TransactionContext tx, TableRef table, long tenantId, long projectId) {
        DatasourceCoord c = table.datasource();
        String dsKey = c.dsKey();
        tx.run("""
                MERGE (d:Datasource {dsKey:$dk})
                  ON CREATE SET d.id=$dk, d.tenantId=$t, d.projectId=$p, d.ip=$ip, d.port=$port,
                                d.database=$db, d.name=$nm
                """, params("dk", dsKey, "t", tenantId, "p", projectId, "ip", c.ip(),
                "port", c.port(), "db", c.database(),
                "nm", c.fallbackName() != null ? c.fallbackName() : c.database())).consume();
        String tk = tableKey(c, table.qualifiedName());
        tx.run("""
                MERGE (t:Table {tableKey:$tk})
                  ON CREATE SET t.id=$tk, t.datasourceId=$dk, t.tenantId=$t, t.projectId=$p,
                                t.qualifiedName=$qn, t.layer=$ly
                """, params("tk", tk, "dk", dsKey, "t", tenantId, "p", projectId,
                "qn", table.qualifiedName(), "ly", table.layer())).consume();
        tx.run("MATCH (d:Datasource {dsKey:$dk}),(t:Table {tableKey:$tk}) MERGE (d)-[:HAS_TABLE]->(t)",
                params("dk", dsKey, "tk", tk)).consume();
        return tk;
    }

    /** MERGE :Column + HAS_COLUMN，返回 columnKey（幂等）。dataType/ordinal 预留写入位（catalog 方案 D，v1 透传 null）。 */
    private String ensureColumn(TransactionContext tx, String tableKey, String colName,
                                String dataType, Integer ordinal,
                                long tenantId, long projectId) {
        String ck = columnKey(tableKey, colName);
        tx.run("""
                MERGE (c:Column {columnKey:$ck})
                  ON CREATE SET c.id=$ck, c.tenantId=$t, c.projectId=$p, c.name=$nm,
                                c.tableKey=$tk, c.dataType=$dt, c.ordinal=$ord
                """, params("ck", ck, "t", tenantId, "p", projectId,
                "nm", colName, "tk", tableKey, "dt", dataType, "ord", ordinal)).consume();
        tx.run("MATCH (t:Table {tableKey:$tk}),(c:Column {columnKey:$ck}) MERGE (t)-[:HAS_COLUMN]->(c)",
                params("tk", tableKey, "ck", ck)).consume();
        return ck;
    }

    // ── 合成 key + 规范化 ─────────────────────────────────────────────────────

    private static String taskKey(long tenantId, long taskDefId) {
        return tenantId + "|task|" + taskDefId;
    }

    private static String tableKey(DatasourceCoord c, String qualifiedName) {
        return c.dsKey() + "|" + norm(qualifiedName);
    }

    private static String columnKey(String tableKey, String col) {
        return tableKey + "|" + norm(col);
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase();
    }

    private static String nameOf(Enum<?> e) {
        return e == null ? null : e.name();
    }

    private static Map<String, Object> params(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
