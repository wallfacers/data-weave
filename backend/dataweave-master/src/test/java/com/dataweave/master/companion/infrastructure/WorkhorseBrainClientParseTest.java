package com.dataweave.master.companion.infrastructure;

import com.dataweave.master.companion.domain.PatrolResult;
import com.dataweave.master.companion.domain.ReportSeverities;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WorkhorseBrainClient 的纯解析逻辑测试（无 HTTP，static 方法）：
 * parsePatrolJson / stripToJson / extractText —— brain 产出 JSON 的容错解析是 SC-007 兜底的关键。
 */
class WorkhorseBrainClientParseTest {

    @Test
    void parsePatrolJson_validObject() {
        String json = "{\"severity\":\"DANGER\",\"title\":\"3 任务失败\",\"summary\":\"ETL 链路三连失败\","
                + "\"detail\":{\"objects\":[{\"type\":\"TASK\",\"id\":\"9101\",\"name\":\"GMV 同步\"}],"
                + "\"aggregateCount\":3,\"suggestions\":[\"重跑\"]}}";
        PatrolResult r = WorkhorseBrainClient.parsePatrolJson(json);
        assertThat(r.ok()).isTrue();
        assertThat(r.severity()).isEqualTo(ReportSeverities.DANGER);
        assertThat(r.title()).isEqualTo("3 任务失败");
        assertThat(r.summary()).isEqualTo("ETL 链路三连失败");
        assertThat(r.detailJson()).contains("9101").contains("aggregateCount");
    }

    @Test
    void parsePatrolJson_stripsMarkdownFenceAndPreamble() {
        String fenced = "好的，本轮结果如下：\n```json\n{\"severity\":\"OK\",\"title\":\"一切正常\"}\n```\n";
        PatrolResult r = WorkhorseBrainClient.parsePatrolJson(fenced);
        assertThat(r.ok()).isTrue();
        assertThat(r.severity()).isEqualTo(ReportSeverities.OK);
        assertThat(r.title()).isEqualTo("一切正常");
        // summary 缺失 → 回退 title
        assertThat(r.summary()).isEqualTo("一切正常");
        // detail 缺失 → "{}"
        assertThat(r.detailJson()).isEqualTo("{}");
    }

    @Test
    void parsePatrolJson_missingSeverityOrTitleFails() {
        assertThat(WorkhorseBrainClient.parsePatrolJson("{\"title\":\"x\"}").ok()).isFalse();   // 缺 severity
        assertThat(WorkhorseBrainClient.parsePatrolJson("{\"severity\":\"OK\"}").ok()).isFalse(); // 缺 title
    }

    @Test
    void parsePatrolJson_unknownSeverityFails() {
        PatrolResult r = WorkhorseBrainClient.parsePatrolJson("{\"severity\":\"CRITICAL\",\"title\":\"x\"}");
        assertThat(r.ok()).isFalse();   // CRITICAL 不在枚举 → failed（防 brain 自造严重度）
    }

    @Test
    void parsePatrolJson_nonJsonAndEmptyFail() {
        assertThat(WorkhorseBrainClient.parsePatrolJson("没有任何花括号").ok()).isFalse();
        assertThat(WorkhorseBrainClient.parsePatrolJson("").ok()).isFalse();
        assertThat(WorkhorseBrainClient.parsePatrolJson(null).ok()).isFalse();
    }

    @Test
    void stripToJson_plainFencedAndPreamble() {
        assertThat(WorkhorseBrainClient.stripToJson("{\"a\":1}")).isEqualTo("{\"a\":1}");
        assertThat(WorkhorseBrainClient.stripToJson("```json\n{\"a\":1}\n```")).isEqualTo("{\"a\":1}");
        assertThat(WorkhorseBrainClient.stripToJson("报告: {\"a\":1} 完")).isEqualTo("{\"a\":1}");
        assertThat(WorkhorseBrainClient.stripToJson("no braces")).isNull();
    }

    @Test
    void extractText_textOrDeltaField() {
        assertThat(WorkhorseBrainClient.extractText("{\"text\":\"hello\"}")).isEqualTo("hello");
        assertThat(WorkhorseBrainClient.extractText("{\"delta\":\"hi\"}")).isEqualTo("hi");
        assertThat(WorkhorseBrainClient.extractText("{\"type\":\"tool_call_start\"}")).isNull();
        assertThat(WorkhorseBrainClient.extractText("garbage")).isNull();
        assertThat(WorkhorseBrainClient.extractText(null)).isNull();
    }
}
