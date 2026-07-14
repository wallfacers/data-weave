package com.dataweave.master.application.incident;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 067 T023：修复提案解析必须严格——绝不能拿半截/空白脚本覆盖任务定义。
 */
class FixProposalPromptTest {

    private final FixProposalPrompt prompt = new FixProposalPrompt();

    @Test
    void parse_validJson_returnsContentAndSummary() {
        String raw = "{\"content\":\"select 1 from t\",\"changeSummary\":\"fixed typo in table name\"}";
        Optional<FixProposalPrompt.FixProposal> parsed = prompt.parse(raw);
        assertThat(parsed).isPresent();
        assertThat(parsed.get().content()).isEqualTo("select 1 from t");
        assertThat(parsed.get().changeSummary()).isEqualTo("fixed typo in table name");
    }

    @Test
    void parse_withSurroundingProseAndFences_extractsJsonBlock() {
        String raw = "Sure, here is the fix:\n```json\n{\"content\":\"select 2\",\"changeSummary\":\"x\"}\n```\nDone.";
        Optional<FixProposalPrompt.FixProposal> parsed = prompt.parse(raw);
        assertThat(parsed).isPresent();
        assertThat(parsed.get().content()).isEqualTo("select 2");
    }

    @Test
    void parse_blankContent_returnsEmpty() {
        assertThat(prompt.parse("{\"content\":\"\",\"changeSummary\":\"x\"}")).isEmpty();
    }

    @Test
    void parse_missingContentField_returnsEmpty() {
        assertThat(prompt.parse("{\"changeSummary\":\"x\"}")).isEmpty();
    }

    @Test
    void parse_noJsonObject_returnsEmpty() {
        assertThat(prompt.parse("I cannot generate a fix for this.")).isEmpty();
    }

    @Test
    void parse_blankInput_returnsEmpty() {
        assertThat(prompt.parse("")).isEmpty();
        assertThat(prompt.parse(null)).isEmpty();
    }

    @Test
    void parse_missingChangeSummary_defaultsToEmptyString() {
        Optional<FixProposalPrompt.FixProposal> parsed = prompt.parse("{\"content\":\"select 1\"}");
        assertThat(parsed).isPresent();
        assertThat(parsed.get().changeSummary()).isEmpty();
    }

    @Test
    void systemPrompt_includesTargetLanguage() {
        assertThat(prompt.systemPrompt("en-US")).contains("en-US");
        assertThat(prompt.systemPrompt(null)).contains("zh-CN");
    }
}
