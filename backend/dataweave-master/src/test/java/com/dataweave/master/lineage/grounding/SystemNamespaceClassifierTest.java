package com.dataweave.master.lineage.grounding;

import com.dataweave.master.application.lineage.grounding.SystemNamespaceClassifier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T016：{@link SystemNamespaceClassifier} 单测——按引擎判系统命名空间 + 配置追加 + 裸名不误判。
 */
class SystemNamespaceClassifierTest {

    private final SystemNamespaceClassifier base = new SystemNamespaceClassifier("");

    @Test
    void postgres_system_schemas() {
        assertThat(base.isSystem("POSTGRES", "pg_catalog.pg_class")).isTrue();
        assertThat(base.isSystem("POSTGRES", "pg_toast.foo")).isTrue();
        assertThat(base.isSystem("POSTGRES", "information_schema.columns")).isTrue();
        assertThat(base.isSystem("POSTGRES", "dw.orders")).isFalse();
    }

    @Test
    void mysql_system_schemas() {
        assertThat(base.isSystem("MYSQL", "information_schema.columns")).isTrue();
        assertThat(base.isSystem("MYSQL", "performance_schema.events")).isTrue();
        assertThat(base.isSystem("MYSQL", "sys.x")).isTrue();
        assertThat(base.isSystem("MYSQL", "app.users")).isFalse();
    }

    @Test
    void common_information_schema_applies_to_any_engine() {
        assertThat(base.isSystem("H2", "INFORMATION_SCHEMA.TABLES")).isTrue();  // 大小写不敏感
        assertThat(base.isSystem("H2", "PUBLIC.orders")).isFalse();
        assertThat(base.isSystem(null, "information_schema.x")).isTrue();
    }

    @Test
    void bare_table_name_never_system() {
        assertThat(base.isSystem("POSTGRES", "orders")).isFalse();
        assertThat(base.isSystem("POSTGRES", "pg_class")).isFalse();  // 无 schema 段 → 不误判
    }

    @Test
    void configured_extra_schemas_are_added() {
        SystemNamespaceClassifier withExtra = new SystemNamespaceClassifier("my_meta, audit_sys");
        assertThat(withExtra.isSystem("POSTGRES", "my_meta.t")).isTrue();
        assertThat(withExtra.isSystem("H2", "audit_sys.t")).isTrue();
        assertThat(withExtra.isSystem("POSTGRES", "pg_catalog.x")).isTrue();  // 内置仍生效
        assertThat(withExtra.isSystem("POSTGRES", "dw.t")).isFalse();
    }
}
