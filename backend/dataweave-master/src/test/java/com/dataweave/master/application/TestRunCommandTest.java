package com.dataweave.master.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TEST_RUN command 编解码：携带编辑器临时内容时 base64 信封；无内容退化为纯 bizDate（兼容 MCP/rerun 旧路径）。
 */
class TestRunCommandTest {

    @Test
    void encode_noOverride_returnsPlainBizDate() {
        assertThat(TestRunCommand.encode("2026-06-20", null, null, null)).isEqualTo("2026-06-20");
        assertThat(TestRunCommand.encode(null, "", "  ", "")).isNull();
    }

    @Test
    void decode_plainBizDate_isBackwardCompatible() {
        // MCP test_run / 历史 rerun 传纯 bizDate（无分隔符）
        TestRunCommand.Decoded d = TestRunCommand.decode("2026-06-20");
        assertThat(d.bizDate()).isEqualTo("2026-06-20");
        assertThat(d.content()).isNull();
        assertThat(d.paramsJson()).isNull();
        assertThat(d.type()).isNull();
    }

    @Test
    void decode_null_isSafe() {
        TestRunCommand.Decoded d = TestRunCommand.decode(null);
        assertThat(d.bizDate()).isNull();
        assertThat(d.content()).isNull();
    }

    @Test
    void roundTrip_withMultilineContentAndSpecialChars() {
        String content = "select *\nfrom user\nwhere name = 'O''Brien' -- 含引号/换行/分号;\n";
        String params = "{\"region\":\"华东\"}";
        String encoded = TestRunCommand.encode("2026-06-20", content, params, "SQL");
        TestRunCommand.Decoded d = TestRunCommand.decode(encoded);

        assertThat(d.bizDate()).isEqualTo("2026-06-20");
        assertThat(d.content()).isEqualTo(content);
        assertThat(d.paramsJson()).isEqualTo(params);
        assertThat(d.type()).isEqualTo("SQL");
    }

    @Test
    void roundTrip_withNullBizDate() {
        String encoded = TestRunCommand.encode(null, "echo hi", null, "ECHO");
        TestRunCommand.Decoded d = TestRunCommand.decode(encoded);
        assertThat(d.bizDate()).isNull();
        assertThat(d.content()).isEqualTo("echo hi");
        assertThat(d.type()).isEqualTo("ECHO");
        assertThat(d.paramsJson()).isNull();
    }
}
