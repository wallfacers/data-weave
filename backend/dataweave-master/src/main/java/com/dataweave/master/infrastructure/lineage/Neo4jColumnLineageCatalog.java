package com.dataweave.master.infrastructure.lineage;

import com.dataweave.master.application.lineage.ColumnLineageCatalog;
import com.dataweave.master.application.lineage.ColumnMeta;
import com.dataweave.master.application.lineage.TableSchema;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Optional;

/**
 * {@link ColumnLineageCatalog} 的 neo4j 实现：从 {@code (:Table)-[:HAS_COLUMN]->(:Column)}
 * 读取已 seed 的列元数据，按 ordinal 有序回组 {@link TableSchema}。
 *
 * <p>仅在 {@code lineage.column-catalog.type=neo4j} 时装配；查询失败返回 empty（永不抛）。
 */
@Component
@ConditionalOnProperty(name = "lineage.column-catalog.type", havingValue = "neo4j")
public class Neo4jColumnLineageCatalog implements ColumnLineageCatalog {

    private static final Logger log = LoggerFactory.getLogger(Neo4jColumnLineageCatalog.class);

    private final Driver driver;

    public Neo4jColumnLineageCatalog(Driver driver) {
        this.driver = driver;
    }

    @Override
    public Optional<TableSchema> lookupTable(long tenantId, long projectId, String qualifiedName) {
        if (qualifiedName == null || qualifiedName.isBlank()) {
            return Optional.empty();
        }
        try (Session session = driver.session()) {
            var result = session.run("""
                    MATCH (t:Table {tenantId:$tid, projectId:$pid, qualifiedName:$qn})
                          -[:HAS_COLUMN]->(c:Column)
                    RETURN c.name AS name, c.dataType AS dataType, c.ordinal AS ordinal
                    ORDER BY c.ordinal
                    """,
                    Map.of("tid", tenantId, "pid", projectId, "qn", qualifiedName));
            List<ColumnMeta> columns = new ArrayList<>();
            while (result.hasNext()) {
                var row = result.next();
                String name = row.get("name", "");
                String dataType = row.get("dataType", (String) null);
                int ordinal = columns.size(); // fallback: list order
                Object ordObj = row.get("ordinal");
                if (ordObj instanceof Number n) {
                    ordinal = n.intValue();
                }
                columns.add(new ColumnMeta(name, dataType, ordinal));
            }
            if (columns.isEmpty()) {
                return Optional.empty();
            }
            // ensure ordered by ordinal
            columns.sort(Comparator.comparingInt(ColumnMeta::ordinal));
            return Optional.of(new TableSchema(qualifiedName, columns));
        } catch (Exception e) {
            log.debug("Neo4jColumnLineageCatalog lookupTable failed for {}: {}", qualifiedName, e.getMessage());
            return Optional.empty();
        }
    }
}
