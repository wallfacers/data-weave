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
                             List<IoEdge> ioEdges, List<ColumnEdge> columnEdges,
                             java.util.Map<String, ? extends java.util.List<? extends com.dataweave.master.domain.lineage.LineageStore.DeclaredColumn>> declaredSchemas) {
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

                // 2.5-024: 独立 seed :Column（声明 schema → 先于 extract，FR-009）
                if (declaredSchemas != null && !declaredSchemas.isEmpty()) {
                    for (var entry : declaredSchemas.entrySet()) {
                        String tableName = entry.getKey();
                        // 用占位 datasource 临时确保表节点存在（无坐标→降级身份）
                        var dummyCoord = new DatasourceCoord(tenantId, projectId, null, null, null, "declared");
                        var tempRef = new TableRef(dummyCoord, tableName, null);
                        String tk = ensureTable(tx, tempRef, tenantId, projectId);
                        var schemaCols = entry.getValue();
                        for (int i = 0; i < schemaCols.size(); i++) {
                            var col = schemaCols.get(i);
                            ensureColumn(tx, tk, col.name(), col.dataType(), col.ordinal() >= 0 ? col.ordinal() : i, tenantId, projectId);
                        }
                    }
                }

                // 3+4. MERGE 表节点 + CREATE READS/WRITES（顺带建 datasource/HAS_TABLE）
                record EdgeRef(String tableKey, IoEdge edge) {}
                List<EdgeRef> readRefs = new ArrayList<>();
                List<EdgeRef> writeRefs = new ArrayList<>();
                for (IoEdge e : edges) {
                    String tk = ensureTable(tx, e.table(), tenantId, projectId);
                    if (e.direction() == Direction.READS) {
                        readRefs.add(new EdgeRef(tk, e));
                    } else {
                        writeRefs.add(new EdgeRef(tk, e));
                    }
                    String relType = e.direction() == Direction.READS ? "READS" : "WRITES";
                    // modelVersion 仅 SCRIPT_MODEL 边有值（041 FR-015）；null 属性 neo4j 不落
                    tx.run("""
                            MATCH (n:Task {taskKey:$kk}),(t:Table {tableKey:$tk})
                            CREATE (n)-[:%s {source:$s,confidence:$c,version:$v,taskDefId:$td,modelVersion:$mv}]->(t)
                            """.formatted(relType),
                            params("kk", taskKey, "tk", tk, "s", nameOf(e.source()),
                                    "c", nameOf(e.confidence()), "v", v, "td", taskDefId,
                                    "mv", e.modelVersion())).consume();
                }

                // 派生 FLOWS_TO：READ 表 × WRITE 表（带 taskDefId；跳过自环）。
                // 041：补 source/confidence（取两端较弱边，修读侧 FlowEdgeView.confidence 落空的既有缝）
                for (EdgeRef r : readRefs) {
                    for (EdgeRef w : writeRefs) {
                        if (r.tableKey().equals(w.tableKey())) {
                            continue;
                        }
                        IoEdge weaker = weakerOf(r.edge(), w.edge());
                        tx.run("""
                                MATCH (a:Table {tableKey:$rk}),(b:Table {tableKey:$wk})
                                CREATE (a)-[:FLOWS_TO {taskDefId:$td,source:$s,confidence:$c,modelVersion:$mv}]->(b)
                                """, params("rk", r.tableKey(), "wk", w.tableKey(), "td", taskDefId,
                                "s", nameOf(weaker.source()), "c", nameOf(weaker.confidence()),
                                "mv", weaker.modelVersion())).consume();
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
                            CREATE (a)-[:DERIVES_FROM {taskDefId:$td,transform:$tr,confidence:$cf,source:$src}]->(b)
                            """, params("sck", srcCk, "dck", dstCk, "td", taskDefId,
                            "tr", nameOf(ce.transform()), "cf", nameOf(ce.confidence()),
                            "src", nameOf(ce.source()))).consume();
                }
                return null;
            });
        }
        log.debug("recordTaskIo: tenant={} project={} taskDef={} io={} col={}",
                tenantId, projectId, taskDefId, edges.size(), cols.size());
    }

    @Override
    public void applyCorrection(long tenantId, long projectId, long taskDefId,
                                Direction direction, String tableKey, String columnKey, boolean remove) {
        String taskKey = taskKey(tenantId, taskDefId);
        String rel = direction == Direction.READS ? "READS" : "WRITES";
        boolean columnLevel = columnKey != null && !columnKey.isBlank();
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                if (columnLevel) {
                    String colKey = tableKey + "|" + norm(columnKey);
                    if (remove) {
                        // 列级剔除：该任务落在此列上的 DERIVES_FROM（方向按裁决：WRITE=入边，READ=出边）
                        String pattern = direction == Direction.READS
                                ? "(c:Column {columnKey:$ck})-[d:DERIVES_FROM {taskDefId:$td}]->()"
                                : "()-[d:DERIVES_FROM {taskDefId:$td}]->(c:Column {columnKey:$ck})";
                        tx.run("MATCH " + pattern + " DELETE d",
                                params("ck", colKey, "td", taskDefId)).consume();
                    } else {
                        String pattern = direction == Direction.READS
                                ? "(c:Column {columnKey:$ck})-[d:DERIVES_FROM {taskDefId:$td}]->()"
                                : "()-[d:DERIVES_FROM {taskDefId:$td}]->(c:Column {columnKey:$ck})";
                        tx.run("MATCH " + pattern + " SET d.confidence='CONFIRMED', d.humanState='CONFIRMED'",
                                params("ck", colKey, "td", taskDefId)).consume();
                    }
                    return null;
                }
                if (remove) {
                    tx.run("MATCH (:Task {taskKey:$k})-[r:" + rel + "]->(:Table {tableKey:$tk}) DELETE r",
                            params("k", taskKey, "tk", tableKey)).consume();
                    // 该任务的派生 FLOWS_TO：READ 剔除删出边侧，WRITE 剔除删入边侧
                    String flowPattern = direction == Direction.READS
                            ? "(a:Table {tableKey:$tk})-[f:FLOWS_TO {taskDefId:$td}]->()"
                            : "()-[f:FLOWS_TO {taskDefId:$td}]->(b:Table {tableKey:$tk})";
                    tx.run("MATCH " + flowPattern + " DELETE f",
                            params("tk", tableKey, "td", taskDefId)).consume();
                } else {
                    tx.run("MATCH (:Task {taskKey:$k})-[r:" + rel + "]->(:Table {tableKey:$tk}) "
                                    + "SET r.confidence='CONFIRMED', r.humanState='CONFIRMED'",
                            params("k", taskKey, "tk", tableKey)).consume();
                    String flowPattern = direction == Direction.READS
                            ? "(a:Table {tableKey:$tk})-[f:FLOWS_TO {taskDefId:$td}]->()"
                            : "()-[f:FLOWS_TO {taskDefId:$td}]->(b:Table {tableKey:$tk})";
                    tx.run("MATCH " + flowPattern + " SET f.confidence='CONFIRMED', f.humanState='CONFIRMED'",
                            params("tk", tableKey, "td", taskDefId)).consume();
                }
                return null;
            });
        }
        log.debug("applyCorrection: task={} {} {} col={} remove={}",
                taskDefId, rel, tableKey, columnKey, remove);
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
                             TableRef table, Long rowCount, Long bytes, String bizDate, Long taskDefId) {
        if (instanceId == null || table == null) {
            return;
        }
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("""
                        MERGE (r:TaskRun {instanceId:$iid})
                          ON CREATE SET r.id=$iid, r.tenantId=$t, r.projectId=$p, r.taskDefId=$td,
                                        r.bizDate=date($bd)
                        """, params("iid", instanceId, "t", tenantId, "p", projectId, "td", taskDefId, "bd", bizDate)).consume();
                String tk = ensureTable(tx, table, tenantId, projectId);
                tx.run("""
                        MATCH (r:TaskRun {instanceId:$iid}),(t:Table {tableKey:$tk})
                        MERGE (r)-[s:SYNCED {bizDate:$bd}]->(t)
                        SET s.rowCount = $rc, s.bytes = $b
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

    /** FLOWS_TO 的 source/confidence 取两端读写边中较弱者（041）：CONFIRMED&lt;DECLARED&lt;UNVERIFIED&lt;CONFLICT。 */
    private static IoEdge weakerOf(IoEdge a, IoEdge b) {
        return weakness(a.confidence()) >= weakness(b.confidence()) ? a : b;
    }

    private static int weakness(Confidence c) {
        if (c == null) {
            return 0;
        }
        return switch (c) {
            case CONFIRMED -> 0;
            case DECLARED -> 1;
            case UNVERIFIED -> 2;
            case CONFLICT -> 3;
        };
    }

    private static Map<String, Object> params(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
