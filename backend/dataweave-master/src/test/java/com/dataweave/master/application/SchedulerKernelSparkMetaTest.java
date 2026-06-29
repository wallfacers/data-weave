package com.dataweave.master.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * master 提取腿（端到端 sparkMode 注入）：{@code SchedulerKernel.jsonStr} 从任务 params_json 提取
 * {@code _sparkMode/_jarRef/_mainClass}，带入 DispatchCommand → gateway → SparkSubmitRef。
 * 缺键/null 安全返回 null（执行器侧默认 pyspark）。
 */
class SchedulerKernelSparkMetaTest {

    @Test
    void jsonStr_extractsSparkContentFormFromFlatParamsJson() {
        String pj = "{\"_sparkMode\":\"jar\",\"_jarRef\":\"/tmp/app.jar\",\"_mainClass\":\"com.x.Main\"}";
        assertThat(SchedulerKernel.jsonStr(pj, "_sparkMode")).isEqualTo("jar");
        assertThat(SchedulerKernel.jsonStr(pj, "_jarRef")).isEqualTo("/tmp/app.jar");
        assertThat(SchedulerKernel.jsonStr(pj, "_mainClass")).isEqualTo("com.x.Main");
    }

    @Test
    void jsonStr_missingKeyOrNullOrBlank_returnsNull() {
        assertThat(SchedulerKernel.jsonStr("{\"a\":\"b\"}", "_sparkMode")).isNull();
        assertThat(SchedulerKernel.jsonStr(null, "_sparkMode")).isNull();
        assertThat(SchedulerKernel.jsonStr("", "_sparkMode")).isNull();
    }

    @Test
    void jsonStr_handlesEscapedQuotes() {
        assertThat(SchedulerKernel.jsonStr("{\"_mainClass\":\"a\\\"b\"}", "_mainClass")).isEqualTo("a\"b");
    }
}
