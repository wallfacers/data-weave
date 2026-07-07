// ============================================================================
// Testcontainers neo4j 种子数据: 血缘查询集成测试
// Feature: 020-lineage-graph-api + 052-lineage-graph-explorer
// 覆盖场景:
//   1. a→b→c→d 表级 FLOWS_TO 链（多跳上下游/影响面）
//   2. col_a→col_b→col_c 列级 DERIVES_FROM 链（列级流 + transform 透出）
//   3. metric_m COMPUTED_FROM table_a（指标血缘）
//   4. TaskRun SYNCED table_a（运行态 sync-summary）
//   5. 两个租户数据（隔离测试：tenant=1 project=1 vs tenant=2 project=2）
//   6. Task-[:WRITES]->Table 边（052 producers 富属性 + 搜索分层覆盖）
//   7. 更多 layer/qualifiedName（052 search 中缀命中 + 过滤断言）
// ============================================================================

// ─── Tenant 1, Project 1 ────────────────────────────────────────────

// Datasource
CREATE (ds1:Datasource {id: 'ds-1', tenantId: 1, projectId: 1, name: 'ods_db'});

// Tables (a→b→c→d link chain + extra table for search coverage)
CREATE (t1:Table {id: 't-a', tenantId: 1, projectId: 1, qualifiedName: 'ods_orders',        layer: 'ODS', datasourceId: 'ds-1'});
CREATE (t2:Table {id: 't-b', tenantId: 1, projectId: 1, qualifiedName: 'dwd_orders_clean',  layer: 'DWD', datasourceId: 'ds-1'});
CREATE (t3:Table {id: 't-c', tenantId: 1, projectId: 1, qualifiedName: 'dws_orders_agg',    layer: 'DWS', datasourceId: 'ds-1'});
CREATE (t4:Table {id: 't-d', tenantId: 1, projectId: 1, qualifiedName: 'ads_orders_report', layer: 'ADS', datasourceId: 'ds-1'});
// Extra table for search: contains "order_detail" substring; DWD layer for filter coverage
CREATE (tE:Table {id: 't-e', tenantId: 1, projectId: 1, qualifiedName: 'dwd_order_detail',  layer: 'DWD', datasourceId: 'ds-1'});

// Table → Datasource
CREATE (t1)-[:HAS_DATASOURCE]->(ds1);
CREATE (t2)-[:HAS_DATASOURCE]->(ds1);
CREATE (t3)-[:HAS_DATASOURCE]->(ds1);
CREATE (t4)-[:HAS_DATASOURCE]->(ds1);
CREATE (tE)-[:HAS_DATASOURCE]->(ds1);

// FLOWS_TO chain: a→b→c→d (and b→extra)
CREATE (t1)-[:FLOWS_TO {taskDefId: 10, confidence: 'CONFIRMED', source: 'SQL_PARSED'}]->(t2);
CREATE (t2)-[:FLOWS_TO {taskDefId: 11, confidence: 'CONFIRMED', source: 'SQL_PARSED'}]->(t3);
CREATE (t3)-[:FLOWS_TO {taskDefId: 12, confidence: 'UNVERIFIED', source: 'FORM'}]->(t4);
// Extra edge for multi-path testing: b→e
CREATE (t2)-[:FLOWS_TO {taskDefId: 13, confidence: 'CONFIRMED', source: 'SQL_PARSED'}]->(tE);

// Columns for t1 (ods_orders)
CREATE (c1a:Column {id: 'col-a1', tenantId: 1, projectId: 1, name: 'order_id',    dataType: 'BIGINT', ordinal: 1});
CREATE (c1b:Column {id: 'col-a2', tenantId: 1, projectId: 1, name: 'order_date',  dataType: 'DATE', ordinal: 2});
CREATE (c1c:Column {id: 'col-a3', tenantId: 1, projectId: 1, name: 'amount',      dataType: 'DECIMAL', ordinal: 3});
CREATE (t1)-[:HAS_COLUMN]->(c1a);
CREATE (t1)-[:HAS_COLUMN]->(c1b);
CREATE (t1)-[:HAS_COLUMN]->(c1c);

// Columns for t2 (dwd_orders_clean)
CREATE (c2a:Column {id: 'col-b1', tenantId: 1, projectId: 1, name: 'order_id',    dataType: 'BIGINT', ordinal: 1});
CREATE (c2b:Column {id: 'col-b2', tenantId: 1, projectId: 1, name: 'clean_date',  dataType: 'DATE', ordinal: 2});
CREATE (t2)-[:HAS_COLUMN]->(c2a);
CREATE (t2)-[:HAS_COLUMN]->(c2b);

// Columns for t3 (dws_orders_agg)
CREATE (c3a:Column {id: 'col-c1', tenantId: 1, projectId: 1, name: 'order_id',    dataType: 'BIGINT', ordinal: 1});
CREATE (c3b:Column {id: 'col-c2', tenantId: 1, projectId: 1, name: 'total_amount', dataType: 'DECIMAL', ordinal: 2});
CREATE (t3)-[:HAS_COLUMN]->(c3a);
CREATE (t3)-[:HAS_COLUMN]->(c3b);

// Columns for tE (dwd_order_detail)
CREATE (cEa:Column {id: 'col-e1', tenantId: 1, projectId: 1, name: 'detail_id', dataType: 'BIGINT', ordinal: 1});
CREATE (tE)-[:HAS_COLUMN]->(cEa);

// Column-level DERIVES_FROM: col-a3(amount) → col-c2(total_amount, AGGREGATE)
CREATE (c1c)-[:DERIVES_FROM {taskDefId: 11, transform: 'DIRECT', confidence: 'CONFIRMED', source: 'SQL_PARSED'}]->(c2b);
CREATE (c2b)-[:DERIVES_FROM {taskDefId: 12, transform: 'AGGREGATE', confidence: 'UNVERIFIED', source: 'FORM'}]->(c3b);

// Task nodes (for taskDefId references + producers)
CREATE (task10:Task {id: 'task-10', tenantId: 1, projectId: 1, name: 'etl_ods_to_dwd', taskDefId: 10});
CREATE (task11:Task {id: 'task-11', tenantId: 1, projectId: 1, name: 'etl_dwd_to_dws', taskDefId: 11});
CREATE (task12:Task {id: 'task-12', tenantId: 1, projectId: 1, name: 'etl_dws_to_ads', taskDefId: 12});
CREATE (task13:Task {id: 'task-13', tenantId: 1, projectId: 1, name: 'etl_dwd_detail', taskDefId: 13});

// Task-[:WRITES]->Table edges (052 producers 富属性来源)
CREATE (task10)-[:WRITES]->(t2);
CREATE (task11)-[:WRITES]->(t3);
CREATE (task12)-[:WRITES]->(t4);
CREATE (task13)-[:WRITES]->(tE);
// READ edges
CREATE (task10)-[:READS]->(t1);
CREATE (task11)-[:READS]->(t2);
CREATE (task12)-[:READS]->(t3);
CREATE (task13)-[:READS]->(t2);

// Metric COMPUTED_FROM table_a
CREATE (metric1:Metric {id: 'metric-1', tenantId: 1, projectId: 1, name: 'order_count', metricType: 'DERIVED'});
CREATE (metric1)-[:COMPUTED_FROM]->(t1);

// TaskRun SYNCED → table (for sync-summary + attrs syncedRowsToday)
CREATE (run1:TaskRun {id: 'run-1', tenantId: 1, projectId: 1, bizDate: date('2026-06-30'), instanceId: 'inst-1'});
CREATE (run1)-[:SYNCED {rowCount: 15000}]->(t1);
CREATE (run1)-[:SYNCED {rowCount: 14800}]->(t2);
// Today run for syncedRowsToday test
CREATE (runToday:TaskRun {id: 'run-today', tenantId: 1, projectId: 1, bizDate: date(), instanceId: 'inst-today'});
CREATE (runToday)-[:SYNCED {rowCount: 1204882}]->(t4);
CREATE (runToday)-[:SYNCED {rowCount: 5000}]->(tE);

// ─── Tenant 2, Project 2 (for isolation testing) ────────────────────

CREATE (ds2:Datasource {id: 'ds-2', tenantId: 2, projectId: 2, name: 'warehouse_db'});

CREATE (t5:Table {id: 't-x', tenantId: 2, projectId: 2, qualifiedName: 'dw_customers', layer: 'DWD', datasourceId: 'ds-2'});
CREATE (t5)-[:HAS_DATASOURCE]->(ds2);

// Extra table for tenant2 to verify search isolation: similar name but different tenant
CREATE (t6:Table {id: 't-y', tenantId: 2, projectId: 2, qualifiedName: 'dwd_order_detail_bak', layer: 'DWD', datasourceId: 'ds-2'});
CREATE (t6)-[:HAS_DATASOURCE]->(ds2);

CREATE (c5a:Column {id: 'col-x1', tenantId: 2, projectId: 2, name: 'cust_id', dataType: 'BIGINT', ordinal: 1});
CREATE (t5)-[:HAS_COLUMN]->(c5a);

// Column in tenant2 with similar name for isolation test
CREATE (c6a:Column {id: 'col-y1', tenantId: 2, projectId: 2, name: 'detail_id', dataType: 'BIGINT', ordinal: 1});
CREATE (t6)-[:HAS_COLUMN]->(c6a);

CREATE (metric2:Metric {id: 'metric-2', tenantId: 2, projectId: 2, name: 'customer_ltv', metricType: 'DERIVED'});
CREATE (metric2)-[:COMPUTED_FROM]->(t5);

// Task nodes for tenant2
CREATE (task20:Task {id: 'task-20', tenantId: 2, projectId: 2, name: 'etl_tenant2', taskDefId: 20});
CREATE (task20)-[:WRITES]->(t5);
CREATE (task20)-[:WRITES]->(t6);
