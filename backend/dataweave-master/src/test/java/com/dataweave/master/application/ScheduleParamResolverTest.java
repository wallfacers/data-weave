package com.dataweave.master.application;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ScheduleParamResolver} 单测，覆盖 scheduling-parameters spec 全部 scenario。
 *
 * <p>特性 012：平台占位符语法迁移到 {@code {{...}}}（与 shell/SQL 不冲突）。
 * 所有平台占位符用例改用 {@code {{...}}}/{@code {{bizdate}}}；新增证伪断言确认
 * {@code ${VAR}}/{@code ${VAR:-d}}/{@code $(cmd)}/{@code $HOME}/{@code $$}/裸 {@code $bizdate} 全部原样透传。
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
        assertThat(r.resolve("d={{yyyymmdd}}", "2025-03-14", null, ctx())).isEqualTo("d=20250314");
        assertThat(r.resolve("d={{yyyy-mm-dd}}", "2025-03-14", null, ctx())).isEqualTo("d=2025-03-14");
        assertThat(r.resolve("d={{yyyymm}}", "2025-03-14", null, ctx())).isEqualTo("d=202503");
        assertThat(r.resolve("d={{yyyy}}", "2025-03-14", null, ctx())).isEqualTo("d=2025");
    }

    @Test
    void bizDateAcceptsCompactForm() {
        assertThat(r.resolve("{{yyyymmdd}}", "20250314", null, ctx())).isEqualTo("20250314");
    }

    @Test
    void timePrecisionTokenRejected() {
        // {{...}} 不支持 hh/mi/ss：含 h 的 fmt 当作未定义占位符失败
        assertThatThrownBy(() -> r.resolve("{{yyyy-mm-dd hh24:mi:ss}}", "2025-03-14", null, ctx()))
                .isInstanceOf(ScheduleParamResolver.UnresolvedPlaceholderException.class);
    }

    // ── Requirement: Business-date integer offset ──────────────────────

    @Test
    void integerOffsets() {
        assertThat(r.resolve("{{yyyymmdd-1}}", "2025-03-14", null, ctx())).isEqualTo("20250313");
        assertThat(r.resolve("{{yyyymm-1}}", "2025-03-14", null, ctx())).isEqualTo("202502");
        assertThat(r.resolve("{{yyyy-1}}", "2025-03-14", null, ctx())).isEqualTo("2024");
        assertThat(r.resolve("{{yyyymmdd-7*1}}", "2025-03-14", null, ctx())).isEqualTo("20250307");
        assertThat(r.resolve("{{yyyymmdd+2}}", "2025-03-14", null, ctx())).isEqualTo("20250316");
    }

    @Test
    void monthEndRollover() {
        // 2025-03-31 月偏移 -1 → 2025-02（yyyymm 只取年月，月末自动 clamp）
        assertThat(r.resolve("{{yyyymm-1}}", "2025-03-31", null, ctx())).isEqualTo("202502");
    }

    // ── Requirement: System built-in scheduling parameters ─────────────

    @Test
    void builtInParameters() {
        assertThat(r.resolve("{{bizdate}}", "2025-03-14", null, ctx())).isEqualTo("20250314");
        assertThat(r.resolve("{{bizmonth}}", "2025-03-14", null, ctx())).isEqualTo("202502"); // 同月→上月
        assertThat(r.resolve("{{gmtdate}}", "2025-03-14", null, ctx())).isEqualTo("20250315");
        assertThat(r.resolve("{{jobid}}", "2025-03-14", null, ctx())).isEqualTo("7");
        assertThat(r.resolve("{{nodeid}}", "2025-03-14", null, ctx())).isEqualTo("n3");
        assertThat(r.resolve("{{taskid}}", "2025-03-14", null, ctx())).isEqualTo("inst-1");
    }

    @Test
    void bizMonthCrossMonthRule() {
        // biz=2025-02-20, today=2025-03 → 不同月 → 取业务日期月份 202502
        var c = new ScheduleParamResolver.BuiltInContext(null, null, null, LocalDate.of(2025, 3, 15));
        assertThat(r.resolve("{{bizmonth}}", "2025-02-20", null, c)).isEqualTo("202502");
    }

    // ── Requirement: Recursive custom parameter expansion ──────────────

    @Test
    void twoLevelExpansion() {
        assertThat(r.resolve("WHERE dt='{{dt}}'", "2025-03-14", "{\"dt\":\"{{yyyymmdd-1}}\"}", ctx()))
                .isEqualTo("WHERE dt='20250313'");
    }

    @Test
    void recursiveExpansionThroughValueContainingPlaceholders() {
        String params = "{\"biz_dt\":\"{{yyyymmdd-1}}\",\"biz_pt\":\"dt={{biz_dt}}\"}";
        assertThat(r.resolve("INSERT ... WHERE {{biz_pt}}", "2025-03-14", params, ctx()))
                .isEqualTo("INSERT ... WHERE dt=20250313");
    }

    @Test
    void unknownCustomParameterFails() {
        assertThatThrownBy(() -> r.resolve("SELECT {{nope}}", "2025-03-14", null, ctx()))
                .isInstanceOf(ScheduleParamResolver.UnresolvedPlaceholderException.class)
                .hasMessage("schedule.placeholder.undefined");
    }

    @Test
    void circularReferenceDetected() {
        String params = "{\"a\":\"{{b}}\",\"b\":\"{{a}}\"}";
        assertThatThrownBy(() -> r.resolve("{{a}}", "2025-03-14", params, ctx()))
                .isInstanceOf(ScheduleParamResolver.UnresolvedPlaceholderException.class)
                .hasMessage("schedule.param.circular");
    }

    @Test
    void literalNestedPlaceholderRejected() {
        // 嵌套 {{{{x}}}}：占位符内部又现 { → schedule.placeholder.nested
        assertThatThrownBy(() -> r.resolve("SELECT {{{{biz_dt}}}}", "2025-03-14", "{\"biz_dt\":\"x\"}", ctx()))
                .isInstanceOf(ScheduleParamResolver.UnresolvedPlaceholderException.class)
                .hasMessage("schedule.placeholder.nested");
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
        assertThat(r.resolve("{{yyyymmdd}}", "2025-03-14", "", ctx())).isEqualTo("20250314");
        assertThat(r.resolve("{{yyyymmdd}}", "2025-03-14", "{}", ctx())).isEqualTo("20250314");
        assertThat(r.resolve("{{yyyymmdd}}", "2025-03-14", "not-json", ctx())).isEqualTo("20250314");
    }

    // ── 特性 012：shell/SQL 证伪 —— ${...}/$(...)/$word/$$ 等一律原样透传 ──────

    @Test
    void shellDollarBraceVarPassedThrough() {
        // ${VAR}：shell 变量（非已知内置参数），原样透传
        assertThat(r.resolve("echo ${VAR}", null, null, ctx())).isEqualTo("echo ${VAR}");
        // ${BIZ_DATE}：已知内置参数（下划线变体），解析为实际值
        assertThat(r.resolve("PARTITION=\"dt=${BIZ_DATE}\"", "2025-03-14", null, ctx()))
                .isEqualTo("PARTITION=\"dt=20250314\"");
    }

    @Test
    void shellParamExpansionDefaultPassedThrough() {
        // ${VAR:-default}：bash 参数展开（含 ${bizdate:-$(date...)}），原样透传
        String shell = "BIZ_DATE=${bizdate:-$(date -d \"yesterday\" +%Y%m%d)}";
        assertThat(r.resolve(shell, null, null, ctx())).isEqualTo(shell);
        assertThat(r.resolve("v=${X:-d}", null, null, ctx())).isEqualTo("v=${X:-d}");
    }

    @Test
    void shellCommandSubstitutionPassedThrough() {
        // $(cmd)：命令替换，原样透传
        assertThat(r.resolve("ts=$(date '+%s')", null, null, ctx())).isEqualTo("ts=$(date '+%s')");
    }

    @Test
    void shellPlainDollarVarPassedThrough() {
        // $HOME / $$ / $1：裸 shell 变量 / PID / 位置参数，原样透传
        assertThat(r.resolve("echo $HOME", null, null, ctx())).isEqualTo("echo $HOME");
        assertThat(r.resolve("pid=$$", null, null, ctx())).isEqualTo("pid=$$");
        assertThat(r.resolve("arg=$1", null, null, ctx())).isEqualTo("arg=$1");
    }

    @Test
    void bareDollarBuiltInWordNoLongerSpecial() {
        // 特性 012：裸 $bizdate 不再特殊处理，原样透传留给 shell（统一走 {{bizdate}}）
        assertThat(r.resolve("$bizdate", "2025-03-14", null, ctx())).isEqualTo("$bizdate");
        assertThat(r.resolve("now=$gmtdate", "2025-03-14", null, ctx())).isEqualTo("now=$gmtdate");
        assertThat(r.resolve("$jobid $nodeid $taskid", "2025-03-14", null, ctx()))
                .isEqualTo("$jobid $nodeid $taskid");
    }

    @Test
    void mixedShellAndPlatformPlaceholder() {
        // 同一内容里 shell ${VAR} 透传、平台 {{yyyymmdd}} 解析，互不干扰
        assertThat(r.resolve("echo $HOME ${VAR} {{yyyymmdd}}", "2025-03-14", null, ctx()))
                .isEqualTo("echo $HOME ${VAR} 20250314");
    }

    @Test
    void realWorldShellSeedPassedThroughWhenNoPlatformPlaceholder() {
        // origin 真实化种子（整段 bash）无平台占位符 → 原样透传，即便 bizDate 为 null/空也不报错（回归用例）
        // 注：${MY_BIZ_DATE}/${SRC_USER}/${TABLE}/${COUNT} 均为未知 shell 变量（非内置关键词），原样保留；
        // ${BIZ_DATE} 是下划线变体内置词 → 会被解析；使用未知变量确保测试意图一致
        String shell = "#!/bin/bash\n"
                + "BIZ_DATE=${MY_BIZ_DATE:-$(date -d \"yesterday\" +%Y%m%d)}\n"
                + "PARTITION=\"dt=${PARTITION_DATE}\"\n"
                + "sqoop import --username ${SRC_USER} --table ${TABLE} ${COUNT}\n"
                + "echo \"[$(date '+%H:%M:%S')] step\"\npid=$$\narg=$1";
        assertThat(r.resolve(shell, null, null, ctx())).isEqualTo(shell);
        assertThat(r.resolve(shell, "", null, ctx())).isEqualTo(shell);
        assertThat(r.resolve(shell, "   ", null, ctx())).isEqualTo(shell);
    }

    @Test
    void hasPlatformPlaceholderDetection() {
        // 平台占位符：仅 {{...}}
        assertThat(ScheduleParamResolver.hasPlatformPlaceholder("{{yyyymmdd}}")).isTrue();
        assertThat(ScheduleParamResolver.hasPlatformPlaceholder("a{{b}}c")).isTrue();
        assertThat(ScheduleParamResolver.hasPlatformPlaceholder("now={{gmtdate}}")).isTrue();
        // shell/SQL 构造 / 普通文本：非平台占位符
        assertThat(ScheduleParamResolver.hasPlatformPlaceholder("${yyyymmdd}")).isFalse();
        assertThat(ScheduleParamResolver.hasPlatformPlaceholder("${VAR:-d}")).isFalse();
        assertThat(ScheduleParamResolver.hasPlatformPlaceholder("$bizdate")).isFalse();
        assertThat(ScheduleParamResolver.hasPlatformPlaceholder("$(date)")).isFalse();
        assertThat(ScheduleParamResolver.hasPlatformPlaceholder("$HOME")).isFalse();
        assertThat(ScheduleParamResolver.hasPlatformPlaceholder("$$")).isFalse();
        assertThat(ScheduleParamResolver.hasPlatformPlaceholder("$1")).isFalse();
        assertThat(ScheduleParamResolver.hasPlatformPlaceholder("SELECT 1")).isFalse();
        // 单个 { 不是平台占位符
        assertThat(ScheduleParamResolver.hasPlatformPlaceholder("{x}")).isFalse();
        assertThat(ScheduleParamResolver.hasPlatformPlaceholder("")).isFalse();
        assertThat(ScheduleParamResolver.hasPlatformPlaceholder(null)).isFalse();
    }

    @Test
    void emptyBizDateStillFailsForRealPlaceholder() {
        // 真正含平台占位符时，bizDate 空仍应报错（修复不应放过这条）
        assertThatThrownBy(() -> r.resolve("{{yyyymmdd}}", null, null, ctx()))
                .isInstanceOf(ScheduleParamResolver.UnresolvedPlaceholderException.class)
                .hasMessage("schedule.bizdate.empty");
        assertThatThrownBy(() -> r.resolve("{{bizdate}}", "  ", null, ctx()))
                .isInstanceOf(ScheduleParamResolver.UnresolvedPlaceholderException.class)
                .hasMessage("schedule.bizdate.empty");
    }

    @Test
    void multiplePlaceholdersInContent() {
        assertThat(r.resolve("{{yyyymmdd}} and {{yyyy-mm-dd}}", "2025-03-14", null, ctx()))
                .isEqualTo("20250314 and 2025-03-14");
    }

    @Test
    void malformedOffsetFails() {
        // {{yyyymmdd-}}：末尾悬空运算符 → schedule.offset.dangling
        assertThatThrownBy(() -> r.resolve("{{yyyymmdd-}}", "2025-03-14", null, ctx()))
                .isInstanceOf(ScheduleParamResolver.UnresolvedPlaceholderException.class)
                .hasMessage("schedule.offset.dangling");
    }

    @Test
    void illegalOffsetFails() {
        // {{yyyymmdd-1x}}：offset 段不匹配 [-+]\d+(\*\d+)? → schedule.offset.illegal
        assertThatThrownBy(() -> r.resolve("{{yyyymmdd-1x}}", "2025-03-14", null, ctx()))
                .isInstanceOf(ScheduleParamResolver.UnresolvedPlaceholderException.class)
                .hasMessage("schedule.offset.illegal");
    }

    @Test
    void unclosedPlaceholderFails() {
        assertThatThrownBy(() -> r.resolve("{{yyyymmdd", "2025-03-14", null, ctx()))
                .isInstanceOf(ScheduleParamResolver.UnresolvedPlaceholderException.class)
                .hasMessage("schedule.placeholder.unclosed");
    }

    @Test
    void emptyPlaceholderFails() {
        assertThatThrownBy(() -> r.resolve("{{}}", "2025-03-14", null, ctx()))
                .isInstanceOf(ScheduleParamResolver.UnresolvedPlaceholderException.class)
                .hasMessage("schedule.placeholder.empty");
    }

    @Test
    void emptyBizDateFails() {
        assertThatThrownBy(() -> r.resolve("{{yyyymmdd}}", " ", null, ctx()))
                .isInstanceOf(ScheduleParamResolver.UnresolvedPlaceholderException.class)
                .hasMessage("schedule.bizdate.empty");
    }

    @Test
    void illegalBizDateFails() {
        // 长度符合 yyyy-MM-dd 但内容非法（月份越界）→ 解析异常路径
        assertThatThrownBy(() -> r.resolve("{{yyyymmdd}}", "2025-13-40", null, ctx()))
                .isInstanceOf(ScheduleParamResolver.UnresolvedPlaceholderException.class)
                .hasMessage("schedule.bizdate.illegal");
    }

    @Test
    void malformedBizDateFails() {
        // 长度不符合任一格式 → schedule.bizdate.format
        assertThatThrownBy(() -> r.resolve("{{yyyymmdd}}", "2025/03/14", null, ctx()))
                .isInstanceOf(ScheduleParamResolver.UnresolvedPlaceholderException.class)
                .hasMessage("schedule.bizdate.format");
    }
}
