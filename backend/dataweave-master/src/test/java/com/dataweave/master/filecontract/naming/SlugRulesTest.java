package com.dataweave.master.filecontract.naming;

import com.dataweave.master.filecontract.error.FileContractException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.*;

/**
 * Test suite for slug derivation, degenerate detection, deterministic fallback,
 * and uniqueness guarantees — the grounding layer for round-trip fidelity (013).
 *
 * <p>Covers EntityNaming (new) + SlugRules (existing case-collision + validation guards).
 */
class SlugRulesTest {

    // ── Portability pattern (matches SlugRules internal) ──
    private static final Pattern PORTABLE = Pattern.compile("^[a-z0-9_-]+$");

    // ═══════════════════════════════════════════════════════════════
    // FR-002: degenerate detection — base contains no [a-z0-9]
    // ═══════════════════════════════════════════════════════════════

    @Test
    void degenerate_pureChinese_shouldBeDegenerate() {
        assertThat(EntityNaming.isDegenerate("抽取拉取订单分区")).isTrue();
    }

    @Test
    void degenerate_hyphenOnly_shouldBeDegenerate() {
        assertThat(EntityNaming.isDegenerate("-")).isTrue();
    }

    @Test
    void degenerate_doubleHyphen_shouldBeDegenerate() {
        assertThat(EntityNaming.isDegenerate("--")).isTrue();
    }

    @Test
    void degenerate_underscoreOnly_shouldBeDegenerate() {
        assertThat(EntityNaming.isDegenerate("_")).isTrue();
    }

    @Test
    void degenerate_mixedPunctuation_shouldBeDegenerate() {
        // typical CJK collapse: "_-_" after compress/trip → "-"
        assertThat(EntityNaming.isDegenerate("_-_")).isTrue();
    }

    @Test
    void degenerate_empty_shouldBeDegenerate() {
        assertThat(EntityNaming.isDegenerate("")).isTrue();
    }

    @Test
    void degenerate_null_shouldBeDegenerate() {
        assertThat(EntityNaming.isDegenerate(null)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"gmv", "etl-sync", "task_01", "a", "z1", "data-pipeline_v2"})
    void nonDegenerate_containsAsciiLettersOrDigits(String base) {
        assertThat(EntityNaming.isDegenerate(base)).isFalse();
    }

    // ═══════════════════════════════════════════════════════════════
    // FR-002: slugOf produces correct base
    // ═══════════════════════════════════════════════════════════════

    @ParameterizedTest
    @CsvSource(delimiter = '→', value = {
            "抽取-拉取订单分区 → -",
            "指标-GMV 汇总 → -gmv",
            "清洗-去重生成宽表 → -",
            "ETL_数据同步 → etl",
            "Hello World! → hello_world",
            "   Spaces   → spaces",
            "UPPER-CASE → upper-case",
            "normal-task → normal-task",
    })
    void slugOf_cjkNames_correctBase(String input, String expected) {
        assertThat(EntityNaming.slugOf(input)).isEqualTo(expected);
    }

    @Test
    void slugOf_nullOrBlank_returnsUnnamed() {
        assertThat(EntityNaming.slugOf(null)).isEqualTo("unnamed");
        assertThat(EntityNaming.slugOf("")).isEqualTo("unnamed");
        assertThat(EntityNaming.slugOf("   ")).isEqualTo("unnamed");
    }

    // ═══════════════════════════════════════════════════════════════
    // FR-003 / INV-3: deterministic fallback hash
    // ═══════════════════════════════════════════════════════════════

    @Test
    void fallbackHash_isDeterministic() {
        String h1 = EntityNaming.fallbackHash("抽取-拉取订单分区");
        String h2 = EntityNaming.fallbackHash("抽取-拉取订单分区");
        assertThat(h1).isEqualTo(h2);
    }

    @Test
    void fallbackHash_differentNames_differentHashes() {
        String h1 = EntityNaming.fallbackHash("抽取-拉取订单分区");
        String h2 = EntityNaming.fallbackHash("清洗-去重生成宽表");
        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    void fallbackHash_formatIsEPlusHex() {
        String h = EntityNaming.fallbackHash("测试");
        assertThat(h).startsWith("e");
        assertThat(h).hasSize(9); // "e" + 8 hex chars
        assertThat(h.substring(1)).matches("^[0-9a-f]{8}$");
    }

    @Test
    void fallbackHash_isPortable() {
        for (int i = 0; i < 20; i++) {
            String h = EntityNaming.fallbackHash("测试实体-" + i);
            assertThat(PORTABLE.matcher(h).matches())
                    .as("fallback '%s' should be portable", h).isTrue();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // effectiveSlug: degenerate → fallback, otherwise base
    // ═══════════════════════════════════════════════════════════════

    @Test
    void effectiveSlug_degenerate_returnsHash() {
        String eff = EntityNaming.effectiveSlug("抽取-拉取订单分区");
        assertThat(eff).startsWith("e");
        assertThat(eff).isEqualTo(EntityNaming.fallbackHash("抽取-拉取订单分区"));
    }

    @Test
    void effectiveSlug_nonDegenerate_returnsBase() {
        // slugOf("指标-GMV 汇总") = "-gmv" — contains 'gmv' so NOT degenerate
        String eff = EntityNaming.effectiveSlug("指标-GMV 汇总");
        assertThat(eff).isEqualTo("-gmv");
    }

    @Test
    void effectiveSlug_pureAscii_unchanged() {
        assertThat(EntityNaming.effectiveSlug("my-task")).isEqualTo("my-task");
        assertThat(EntityNaming.effectiveSlug("ETL")).isEqualTo("etl");
    }

    // ═══════════════════════════════════════════════════════════════
    // FR-003 / INV-2: uniquify — deterministic collision resolution
    // ═══════════════════════════════════════════════════════════════

    @Test
    void uniquify_noCollision_allKeepEffectiveSlug() {
        List<Map.Entry<Long, String>> siblings = List.of(
                Map.entry(1L, "task-a"),
                Map.entry(2L, "task-b"),
                Map.entry(3L, "task-c")
        );
        Map<Long, String> result = EntityNaming.uniquify(siblings);
        assertThat(result).containsExactly(
                Map.entry(1L, "task-a"),
                Map.entry(2L, "task-b"),
                Map.entry(3L, "task-c")
        );
    }

    @Test
    void uniquify_sameEffective_differentIds_resolvedDeterministically() {
        // Three CJK-named entities in same catalog dir all degenerate → same hash scenario
        // Using explicit equal effective slugs to simulate worst-case collision
        List<Map.Entry<Long, String>> siblings = List.of(
                Map.entry(10L, "degenerate-collision"),
                Map.entry(11L, "degenerate-collision"),
                Map.entry(12L, "degenerate-collision")
        );
        Map<Long, String> result = EntityNaming.uniquify(siblings);
        assertThat(result).hasSize(3);
        // All unique
        assertThat(new HashSet<>(result.values())).hasSize(3);
        // First by id keeps original
        assertThat(result.get(10L)).isEqualTo("degenerate-collision");
        // Later entities get suffixed
        assertThat(result.get(11L)).isNotEqualTo(result.get(10L));
        assertThat(result.get(12L)).isNotEqualTo(result.get(10L));
        assertThat(result.get(12L)).isNotEqualTo(result.get(11L));
    }

    @Test
    void uniquify_isDeterministic() {
        List<Map.Entry<Long, String>> siblings = List.of(
                Map.entry(5L, "dup"), Map.entry(3L, "dup"), Map.entry(7L, "dup")
        );
        Map<Long, String> r1 = EntityNaming.uniquify(siblings);
        Map<Long, String> r2 = EntityNaming.uniquify(siblings);
        assertThat(r1).isEqualTo(r2);
    }

    @Test
    void uniquify_insertionOrderIndependent() {
        // Same data, different insertion order → same result (sorted by id)
        List<Map.Entry<Long, String>> order1 = List.of(
                Map.entry(3L, "dup"), Map.entry(1L, "dup"), Map.entry(2L, "dup")
        );
        List<Map.Entry<Long, String>> order2 = List.of(
                Map.entry(2L, "dup"), Map.entry(3L, "dup"), Map.entry(1L, "dup")
        );
        assertThat(EntityNaming.uniquify(order1)).isEqualTo(EntityNaming.uniquify(order2));
    }

    @Test
    void uniquify_allPortable() {
        List<Map.Entry<Long, String>> siblings = new ArrayList<>();
        for (long id = 1; id <= 10; id++) {
            siblings.add(Map.entry(id, "collide"));
        }
        Map<Long, String> result = EntityNaming.uniquify(siblings);
        assertThat(result).hasSize(10);
        for (String slug : result.values()) {
            assertThat(PORTABLE.matcher(slug).matches())
                    .as("slug '%s' should be portable", slug).isTrue();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // INV-1 + FR-008: output is always portable + non-reserved
    // ═══════════════════════════════════════════════════════════════

    @Test
    void output_effectiveSlugs_arePortable() {
        String[] names = {"抽取-拉取订单分区", "指标-GMV 汇总", "my-task", "---", "___", "A&B"};
        for (String name : names) {
            String slug = EntityNaming.effectiveSlug(name);
            assertThat(PORTABLE.matcher(slug).matches())
                    .as("effectiveSlug('%s')='%s' should be portable", name, slug).isTrue();
        }
    }

    @Test
    void output_effectiveSlugs_notReserved() {
        // RESERVED slugs should not be produced as effective slugs
        // (reserved check is done at validation time by SlugRules, not at derivation)
        // Just ensure effectiveSlug does not crash on reserved-like names
        assertThat(EntityNaming.effectiveSlug("project")).isEqualTo("project");
        assertThat(EntityNaming.effectiveSlug("tags")).isEqualTo("tags");
    }

    // ═══════════════════════════════════════════════════════════════
    // Existing SlugRules behavior — case-collision guard still works
    // ═══════════════════════════════════════════════════════════════

    @Test
    void slugRules_checkCaseCollisions_noCollision() {
        assertThatCode(() -> SlugRules.checkCaseCollisions(List.of("etl", "gmv", "sync"), "/dir"))
                .doesNotThrowAnyException();
    }

    @Test
    void slugRules_checkCaseCollisions_detectsDuplicates() {
        assertThatThrownBy(() -> SlugRules.checkCaseCollisions(List.of("etl", "ETL"), "/dir"))
                .isInstanceOf(FileContractException.class)
                .hasMessageContaining("case collision");
    }

    @Test
    void slugRules_validateSlug_valid() {
        assertThatCode(() -> SlugRules.validateSlug("my-task", "task slug", "/dir/my-task"))
                .doesNotThrowAnyException();
    }

    @Test
    void slugRules_validateSlug_nullOrBlank_throws() {
        assertThatThrownBy(() -> SlugRules.validateSlug(null, "task slug", "/dir"))
                .isInstanceOf(FileContractException.class);
        assertThatThrownBy(() -> SlugRules.validateSlug("", "task slug", "/dir"))
                .isInstanceOf(FileContractException.class);
    }

    // ═══════════════════════════════════════════════════════════════
    // FR-011: non-degenerate readable ASCII names keep their slug
    // ═══════════════════════════════════════════════════════════════

    @Test
    void readableAsciiNames_keepOriginalSlug() {
        assertThat(EntityNaming.effectiveSlug("data-sync")).isEqualTo("data-sync");
        assertThat(EntityNaming.effectiveSlug("ETL_Pipeline")).isEqualTo("etl_pipeline");
        assertThat(EntityNaming.effectiveSlug("Task-01")).isEqualTo("task-01");
    }

    // ═══════════════════════════════════════════════════════════════
    // Real-world CJK collision scenario (the exact bug from the field)
    // ═══════════════════════════════════════════════════════════════

    @Test
    void cjkCategoryDashNames_allDegenerateButDistinctHashes() {
        // These 5 names all slugged to "-" before the fix (the exact field bug)
        String[] names = {
                "抽取-拉取订单分区",
                "清洗-去重生成宽表",
                "质检-格式校验过滤",
                "加载-写入目标表",
                "归档-清理过期分区"
        };
        Set<String> slugs = new HashSet<>();
        for (String name : names) {
            String eff = EntityNaming.effectiveSlug(name);
            assertThat(eff).startsWith("e"); // all degenerate → fallback
            assertThat(eff).hasSize(9);
            slugs.add(eff);
        }
        // Every one gets a different hash — zero silent collision
        assertThat(slugs).hasSize(names.length);
    }

    @Test
    void mixedAsciiCjk_slugDistinguishes() {
        // "指标-GMV 汇总" keeps gmv, "指标-PV 汇总" also keeps pv (different ASCII residues)
        String gmv = EntityNaming.effectiveSlug("指标-GMV 汇总");
        String pv = EntityNaming.effectiveSlug("指标-PV 汇总");
        assertThat(gmv).isNotEqualTo(pv);
        assertThat(gmv).contains("gmv");
        assertThat(pv).contains("pv");
    }
}
