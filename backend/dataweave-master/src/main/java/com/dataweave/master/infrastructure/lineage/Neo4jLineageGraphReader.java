package com.dataweave.master.infrastructure.lineage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.dataweave.master.lineage.LineageGraphReader;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link LineageGraphReader} 的 neo4j 实现（整合期补齐 020 的 T042）。
 *
 * <p>用 018 的 {@link Driver} @Bean 开只读 managed read 事务执行 020 查询层的 Cypher，
 * 把 {@code org.neo4j.driver.Record} 映射为 {@code Map<String,Object>}（key=RETURN 别名）。
 *
 * <p>韧性：neo4j 不可达时 driver 抛异常，由 {@code LineageQueryService} 上层捕获转
 * {@code lineage.store_unavailable} 降级码（不阻断、不 500 崩）。
 */
@Component
public class Neo4jLineageGraphReader implements LineageGraphReader {

    private static final Logger log = LoggerFactory.getLogger(Neo4jLineageGraphReader.class);

    private final Driver driver;

    public Neo4jLineageGraphReader(Driver driver) {
        this.driver = driver;
    }

    @Override
    public List<Map<String, Object>> execute(String cypher, Map<String, Object> params) {
        Map<String, Object> p = params == null ? Map.of() : params;
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run(cypher, p);
                List<Map<String, Object>> rows = new ArrayList<>();
                while (result.hasNext()) {
                    rows.add(result.next().asMap());
                }
                return rows;
            });
        }
    }
}
