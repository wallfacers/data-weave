package com.dataweave.master.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** TriggerCommand 编解码（手动运行子图范围，design D5）。 */
class TriggerCommandTest {

    @Test
    void fullScope_degradesToBareBizDate() {
        assertThat(TriggerCommand.encode("2026-06-11", "FULL", null)).isEqualTo("2026-06-11");
        assertThat(TriggerCommand.encode("2026-06-11", null, null)).isEqualTo("2026-06-11");
        assertThat(TriggerCommand.encode("2026-06-11", "  ", "n3")).isEqualTo("2026-06-11");
    }

    @Test
    void toNodeScope_roundtrips() {
        String cmd = TriggerCommand.encode("2026-06-11", "TO_NODE", "n3");
        TriggerCommand.Decoded d = TriggerCommand.decode(cmd);
        assertThat(d.bizDate()).isEqualTo("2026-06-11");
        assertThat(d.scope()).isEqualTo("TO_NODE");
        assertThat(d.targetNodeKey()).isEqualTo("n3");
    }

    @Test
    void downstreamScope_roundtrips() {
        TriggerCommand.Decoded d = TriggerCommand.decode(TriggerCommand.encode("2026-06-11", "DOWNSTREAM", "n5"));
        assertThat(d.scope()).isEqualTo("DOWNSTREAM");
        assertThat(d.targetNodeKey()).isEqualTo("n5");
    }

    @Test
    void bareBizDate_decodesAsFull() {
        TriggerCommand.Decoded d = TriggerCommand.decode("2026-06-11");
        assertThat(d.bizDate()).isEqualTo("2026-06-11");
        assertThat(d.scope()).isEqualTo("FULL");
        assertThat(d.targetNodeKey()).isNull();
    }

    @Test
    void nullCommand_decodesAsFull() {
        TriggerCommand.Decoded d = TriggerCommand.decode(null);
        assertThat(d.scope()).isEqualTo("FULL");
        assertThat(d.targetNodeKey()).isNull();
    }

    @Test
    void nodeKeyWithSpecialChars_survivesBase64() {
        // node_key 含分隔符/中文也安全（Base64）
        TriggerCommand.Decoded d = TriggerCommand.decode(TriggerCommand.encode("2026-06-11", "TO_NODE", "node_中文_1"));
        assertThat(d.targetNodeKey()).isEqualTo("node_中文_1");
    }
}
