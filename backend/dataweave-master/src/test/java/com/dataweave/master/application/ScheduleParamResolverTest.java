package com.dataweave.master.application;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ScheduleParamResolver} 单测，覆盖 scheduling-parameters spec 全部 scenario。
 */
class ScheduleParamResolverTest {

    private static final LocalDate TODAY = LocalDate.of(2025, 3, 15);
    private final ScheduleParamResolver r = new ScheduleParamResolver();

    private ScheduleParamResolver.BuiltInContext ctx() {
        return new ScheduleParamResolver.BuiltInContext("7", "n3", "inst-1", TODAY);
    }

    // ── Requirement: Business-date formatting ──────────────────────────

    @Test
    void formatsBusinessDate() {
        assertThat(r.resolve("d=${yyyymmdd}", "2025-03-14", null, ctx())).isEqualTo("d=20250314");
        assertThat(r.resolve("d=${yyyy-mm-dd}", "2025-03-14", null, ctx())).isEqualTo("d=2025-03-14");
        assertThat(r.resolve("d=${yyyymm}", "2025-03-14", null, ctx())).isEqualTo("d=202503");
        assertThat(r.resolve("d=${yyyy}", "2025-03-14", null, ctx())).isEqualTo("d=2025");
    }

    @Test
    void bizDateAcceptsCompactForm() {
        assertThat(r.resolve("${yyyymmdd}", "20250314", null, ctx())).isEqualTo("20250314");
    }

    @Test
    void timePrecisionTokenRejected() {
        // ${...} 不支持 hh/mi/ss：含 h 的 fmt 当作未定义占位符失败
        assertThatThrownBy(() -> r.resolve("${yyyy-mm-dd hh24:mi:ss}", "2025-03-14", null, ctx()))
                .isInstanceOf(ScheduleParamResolver.UnresolvedPlaceholderException.class);
    }

    // ── Requirement: Business-date integer offset ──────────────────────

    @Test
    void integerOffsets() {
        assertThat(r.resolve("${yyyymmdd-1}", "2025-03-14", null, ctx())).isEqualTo("20250313");
        assertThat(r.resolve("${yyyymm-1}", "2025-03-14", null, ctx())).isEqualTo("202502");
        assertThat(r.resolve("${yyyy-1}", "2025-03-14", null, ctx())).isEqualTo("2024");
        assertThat(r.resolve("${yyyymmdd-7*1}", "2025-03-14", null, ctx())).isEqualTo("20250307");
        assertThat(r.resolve("${yyyymmdd+2}", "2025-03-14", null, ctx())).isEqualTo("20250316");
    }

    @Test
    void monthEndRollover() {
        // 2025-03-31 月偏移 -1 → 2025-02（yyyymm 只取年月，月末自动 clamp）
        assertThat(r.resolve("${yyyymm-1}", "2025-03-31", null, ctx())).isEqualTo("202502");
    }

    // ── Requirement: System built-in scheduling parameters ─────────────

    @Test
    void builtInParameters() {
        assertThat(r.resolve("$bizdate", "2025-03-14", null, ctx())).isEqualTo("20250314");
        assertThat(r.resolve("$bizmonth", "2025-03-14", null, ctx())).isEqualTo("202502"); // 同月→上月
        assertThat(r.resolve("$gmtdate", "2025-03-14", null, ctx())).isEqualTo("20250315");
        assertThat(r.resolve("$jobid", "2025-03-14", null, ctx())).isEqualTo("7");
        assertThat(r.resolve("$nodeid", "2025-03-14", null, ctx())).isEqualTo("n3");
        assertThat(r.resolve("$taskid", "2025-03-14", null, ctx())).isEqualTo("inst-1");
    }

    @Test
    void bizMonthCrossMonthRule() {
        // biz=2025-02-20, today=2025-03 → 不同月 → 取业务日期月份 202502
        var c = new ScheduleParamResolver.BuiltInContext(null, null, null, LocalDate.of(2025, 3, 15));
        assertThat(r.resolve("$bizmonth", "2025-02-20", null, c)).isEqualTo("202502");
    }

    // ── Requirement: Recursive custom parameter expansion ──────────────

    @Test
    void twoLevelExpansion() {
        assertThat(r.resolve("WHERE dt='${dt}'", "2025-03-14", "{\"dt\":\"${yyyymmdd-1}\"}", ctx()))
                .isEqualTo("WHERE dt='20250313'");
    }

    @Test
    void recursiveExpansionThroughValueContainingPlaceholders() {
        String params = "{\"biz_dt\":\"${yyyymmdd-1}\",\"biz_pt\":\"dt=${biz_dt}\"}";
        assertThat(r.resolve("INSERT ... WHERE ${biz_pt}", "2025-03-14", params, ctx()))
                .isEqualTo("INSERT ... WHERE dt=20250313");
    }

    @Test
    void unknownCustomParameterFails() {
        assertThatThrownBy(() -> r.resolve("SELECT ${nope}", "2025-03-14", null, ctx()))
                .isInstanceOf(ScheduleParamResolver.UnresolvedPlaceholderException.class)
                .hasMessageContaining("nope");
    }

    @Test
    void circularReferenceDetected() {
        String params = "{\"a\":\"${b}\",\"b\":\"${a}\"}";
        assertThatThrownBy(() -> r.resolve("${a}", "2025-03-14", params, ctx()))
                .isInstanceOf(ScheduleParamResolver.UnresolvedPlaceholderException.class)
                .hasMessageContaining("循环");
    }

    @Test
    void literalNestedPlaceholderRejected() {
        assertThatThrownBy(() -> r.resolve("SELECT ${${biz_dt}}", "2025-03-14", "{\"biz_dt\":\"x\"}", ctx()))
                .isInstanceOf(ScheduleParamResolver.UnresolvedPlaceholderException.class)
                .hasMessageContaining("嵌套");
    }

    // ── Requirement: No-op when content has no placeholders ────────────

    @Test
    void noOpWhenNoPlaceholder() {
        assertThat(r.resolve("SELECT 1", "2025-03-14", null, ctx())).isEqualTo("SELECT 1");
        assertThat(r.resolve(null, "2025-03-14", null, ctx())).isNull();
        assertThat(r.resolve("", "2025-03-14", null, ctx())).isEqualTo("");
    }

    @Test
    void emptyParamsJsonTreatedAsEmpty() {
        assertThat(r.resolve("${yyyymmdd}", "2025-03-14", "", ctx())).isEqualTo("20250314");
        assertThat(r.resolve("${yyyymmdd}", "2025-03-14", "{}", ctx())).isEqualTo("20250314");
        assertThat(r.resolve("${yyyymmdd}", "2025-03-14", "not-json", ctx())).isEqualTo("20250314");
    }

    @Test
    void nonBuiltInDollarWordPreservedAsShellVar() {
        assertThat(r.resolve("echo $HOME ${yyyymmdd}", "2025-03-14", null, ctx()))
                .isEqualTo("echo $HOME 20250314");
    }

    @Test
    void multiplePlaceholdersInContent() {
        assertThat(r.resolve("${yyyymmdd} and ${yyyy-mm-dd}", "2025-03-14", null, ctx()))
                .isEqualTo("20250314 and 2025-03-14");
    }

    @Test
    void malformedOffsetFails() {
        assertThatThrownBy(() -> r.resolve("${yyyymmdd-}", "2025-03-14", null, ctx()))
                .isInstanceOf(ScheduleParamResolver.UnresolvedPlaceholderException.class);
    }
}
