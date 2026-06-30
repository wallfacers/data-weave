package com.dataweave.master.lineage;

import java.util.List;
import java.util.Map;

/**
 * 只读 neo4j 血缘图读取抽象。
 *
 * <p>为 020 查询层提供对桩开发的契约面。{@link com.dataweave.master.application.LineageQueryService}
 * 依赖本接口执行 Cypher 查询并获取行数据。
 *
 * <p>返回统一为 {@code List<Map<String, Object>>}（每行一个 Map，key 为 Cypher 别名，
 * value 为驱动原生值）。真实 neo4j 集成时由 018 适配 {@code org.neo4j.driver.Record} →
 * Map；Testcontainers 测试中由桩实现返回模拟结果。
 *
 * <h3>租户隔离不变量</h3>
 * 查询层在 Cypher 中总是带 {@code WHERE n.tenantId=$tenantId AND n.projectId=$projectId}
 * 过滤；本接口实现确保会话无跨租户泄漏。
 */
public interface LineageGraphReader {

    /**
     * 执行一条只读 Cypher 查询。
     *
     * @param cypher  Cypher 查询语句（含 $参数）
     * @param params  命名参数
     * @return 行数据列表（每行 Map 的 key 为 RETURN 别名）
     * @throws RuntimeException 若 neo4j 不可达
     */
    List<Map<String, Object>> execute(String cypher, Map<String, Object> params);
}
