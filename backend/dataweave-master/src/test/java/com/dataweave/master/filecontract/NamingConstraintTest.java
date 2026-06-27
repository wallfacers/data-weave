package com.dataweave.master.filecontract;

import com.dataweave.master.filecontract.error.FileContractException;
import com.dataweave.master.filecontract.naming.SlugRules;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FR-007a: Portable naming constraints — valid charset, case-collision detection.
 */
class NamingConstraintTest {

    // ---- SlugRules unit tests ----

    @Test
    void validSlugs_pass() {
        // All should pass without exception
        SlugRules.validateSlug("etl", "test", "test.task.yaml");
        SlugRules.validateSlug("orders_etl", "test", "test.task.yaml");
        SlugRules.validateSlug("daily-orders", "test", "test.task.yaml");
        SlugRules.validateSlug("a1_b2-c3", "test", "test.task.yaml");
    }

    @Test
    void uppercaseInSlug_rejected() {
        assertThatThrownBy(() -> SlugRules.validateSlug("ETL", "slug", "file.yaml"))
                .isInstanceOf(FileContractException.class)
                .hasMessageContaining("ETL");
    }

    @Test
    void mixedCaseInSlug_rejected() {
        assertThatThrownBy(() -> SlugRules.validateSlug("OrdersEtl", "slug", "file.yaml"))
                .isInstanceOf(FileContractException.class);
    }

    @Test
    void chineseCharsInSlug_rejected() {
        assertThatThrownBy(() -> SlugRules.validateSlug("订单", "slug", "file.yaml"))
                .isInstanceOf(FileContractException.class);
    }

    @Test
    void spacesInSlug_rejected() {
        assertThatThrownBy(() -> SlugRules.validateSlug("orders etl", "slug", "file.yaml"))
                .isInstanceOf(FileContractException.class);
    }

    @Test
    void specialCharsInSlug_rejected() {
        assertThatThrownBy(() -> SlugRules.validateSlug("orders@etl", "slug", "file.yaml"))
                .isInstanceOf(FileContractException.class);
    }

    @Test
    void emptySlug_rejected() {
        assertThatThrownBy(() -> SlugRules.validateSlug("", "slug", "file.yaml"))
                .isInstanceOf(FileContractException.class);
    }

    @Test
    void nullSlug_rejected() {
        assertThatThrownBy(() -> SlugRules.validateSlug(null, "slug", "file.yaml"))
                .isInstanceOf(FileContractException.class);
    }

    // ---- Reserved names ----

    @Test
    void reservedName_project_rejected() {
        assertThatThrownBy(() -> SlugRules.validateNotReserved("project", "file.yaml"))
                .isInstanceOf(FileContractException.class);
    }

    @Test
    void reservedName_tags_rejected() {
        assertThatThrownBy(() -> SlugRules.validateNotReserved("tags", "file.yaml"))
                .isInstanceOf(FileContractException.class);
    }

    @Test
    void reservedName_folder_rejected() {
        assertThatThrownBy(() -> SlugRules.validateNotReserved("_folder", "file.yaml"))
                .isInstanceOf(FileContractException.class);
    }

    // ---- Case collision ----

    @Test
    void caseCollision_detected() {
        assertThatThrownBy(() -> SlugRules.checkCaseCollisions(
                List.of("etl", "ETL"), "orders/"))
                .isInstanceOf(FileContractException.class);
    }

    @Test
    void noCaseCollision_ok() {
        // Should not throw
        SlugRules.checkCaseCollisions(List.of("etl", "notify", "orders"), "orders/");
    }

    // ---- File-level naming (via bundle deserialize) ----

    @Test
    void taskSlugWithUppercase_returnsWarning() {
        var fc = new FileContract();
        var files = new java.util.LinkedHashMap<String, String>();
        files.put("project.yaml", """
                formatVersion: 1
                code: test
                name: test
                """);
        files.put("BadSlug.task.yaml", """
                formatVersion: 1
                name: bad task
                type: SQL
                """);
        var bundle = new ProjectFileBundle(files);
        var imported = fc.deserialize(bundle);
        // Warning about invalid slug
        var combined = String.join("\n", imported.warnings());
        assertThat(combined).contains("BadSlug");
    }
}
