package com.dataweave.master.application.lineage.grounding;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 系统 / 元数据 schema 分类器（055，契约 system-namespace-classifier.md）。
 *
 * <p>系统表在目录中真实存在（存在性判据会误采纳），必须显式排除（FR-010）。
 * 按数据源引擎 typeCode 划定系统命名空间集合（通用 + 引擎特定），大小写不敏感匹配候选的 schema 段。
 */
@Component
public class SystemNamespaceClassifier {

    /** 通用系统命名空间（所有引擎叠加）。 */
    private static final Set<String> COMMON = Set.of("information_schema");

    private final Set<String> configured;

    public SystemNamespaceClassifier(
            @Value("${lineage.grounding.system-schemas:}") String extraSchemas) {
        Set<String> extra = new HashSet<>();
        if (extraSchemas != null && !extraSchemas.isBlank()) {
            Arrays.stream(extraSchemas.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(s -> s.toLowerCase(Locale.ROOT))
                    .forEach(extra::add);
        }
        this.configured = extra;
    }

    /** 候选限定名是否落在该引擎的系统 / 元数据命名空间。 */
    public boolean isSystem(String engineTypeCode, String qualifiedName) {
        String schema = schemaSegment(qualifiedName);
        if (schema == null) {
            return false;   // 裸表名无 schema 段 → 不误判系统
        }
        String s = schema.toLowerCase(Locale.ROOT);
        if (COMMON.contains(s) || configured.contains(s)) {
            return true;
        }
        return engineSet(engineTypeCode).contains(s);
    }

    /** 取限定名的 schema 段（倒数第二段）；无则 null。 */
    private static String schemaSegment(String qualifiedName) {
        if (qualifiedName == null || qualifiedName.isBlank()) {
            return null;
        }
        String[] parts = qualifiedName.trim().split("\\.");
        return parts.length >= 2 ? parts[parts.length - 2] : null;
    }

    /** 引擎特定系统命名空间集合（已小写）。 */
    private static Set<String> engineSet(String engineTypeCode) {
        if (engineTypeCode == null) {
            return Set.of();
        }
        return switch (engineTypeCode.toUpperCase(Locale.ROOT)) {
            case "POSTGRES" -> Set.of("pg_catalog", "pg_toast");
            case "MYSQL", "MARIADB", "STARROCKS", "DORIS" ->
                    Set.of("mysql", "sys", "performance_schema");
            case "SQLSERVER" -> Set.of("sys");
            case "ORACLE" -> Set.of("sys", "system");
            default -> Set.of();   // H2 / 未知 → 仅通用 information_schema
        };
    }
}
