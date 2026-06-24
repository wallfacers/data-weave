package com.dataweave.api;

import com.dataweave.api.interfaces.dto.RunAgentInput;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RunAgentInput.attachments() 纯单元解析（chat-attachments）：从 forwardedProps.dataweave.attachments
 * 还原实体引用与文件附件，缺字段/非列表时退化为空列表。
 */
class AttachmentParseTest {

    @Test
    void parsesEntityAndFileAttachments() {
        RunAgentInput input = new RunAgentInput();
        input.setForwardedProps(Map.of("dataweave", Map.of(
                "module", "/ops",
                "attachments", List.of(
                        Map.of("kind", "entity", "refType", "task", "refId", "100", "label", "etl_daily"),
                        // 文件附件前端字段是 name（无 label），displayLabel 应回退到 name。
                        Map.of("kind", "file", "fileId", "abc123", "name", "error.log")
                ))));

        List<RunAgentInput.Attachment> atts = input.attachments();

        assertThat(atts).hasSize(2);
        assertThat(atts.get(0).kind()).isEqualTo("entity");
        assertThat(atts.get(0).refType()).isEqualTo("task");
        assertThat(atts.get(0).refId()).isEqualTo("100");
        assertThat(atts.get(0).displayLabel()).isEqualTo("etl_daily");
        assertThat(atts.get(0).isFile()).isFalse();
        assertThat(atts.get(1).isFile()).isTrue();
        assertThat(atts.get(1).fileId()).isEqualTo("abc123");
        assertThat(atts.get(1).name()).isEqualTo("error.log");
        // 文件无 label，展示名回退到 name（修复：曾错误回退到 sha256 fileId）。
        assertThat(atts.get(1).displayLabel()).isEqualTo("error.log");
    }

    @Test
    void noAttachments_returnsEmpty() {
        RunAgentInput input = new RunAgentInput();
        input.setForwardedProps(Map.of("dataweave", Map.of("module", "/ops")));
        assertThat(input.attachments()).isEmpty();

        RunAgentInput none = new RunAgentInput();
        assertThat(none.attachments()).isEmpty();
    }
}
