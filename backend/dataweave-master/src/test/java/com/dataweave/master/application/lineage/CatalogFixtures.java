package com.dataweave.master.application.lineage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 列级血缘单测基座：内存 {@link ColumnLineageCatalog}，无需 neo4j。
 */
public final class CatalogFixtures {

    private CatalogFixtures() {
    }

    /** 全 VARCHAR 列的表。 */
    public static TableSchema table(String name, String... cols) {
        List<ColumnMeta> cs = new ArrayList<>();
        for (int i = 0; i < cols.length; i++) {
            cs.add(new ColumnMeta(cols[i], "VARCHAR", i));
        }
        return new TableSchema(name, cs);
    }

    /** 带类型的表，元素形如 {@code "amount:DECIMAL"}（无冒号默认 VARCHAR）。 */
    public static TableSchema typed(String name, String... nameColonType) {
        List<ColumnMeta> cs = new ArrayList<>();
        for (int i = 0; i < nameColonType.length; i++) {
            String[] p = nameColonType[i].split(":", 2);
            cs.add(new ColumnMeta(p[0], p.length > 1 ? p[1] : "VARCHAR", i));
        }
        return new TableSchema(name, cs);
    }

    /** 用一组表构造大小写无关的 catalog。 */
    public static ColumnLineageCatalog catalog(TableSchema... tables) {
        Map<String, TableSchema> m = new HashMap<>();
        for (TableSchema t : tables) {
            m.put(t.qualifiedName().toLowerCase(), t);
        }
        return (tenantId, projectId, q) -> q == null ? Optional.empty() : Optional.ofNullable(m.get(q.toLowerCase()));
    }
}
