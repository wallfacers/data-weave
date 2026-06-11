package com.dataweave.master.domain;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class Uuid7Test {

    @Test
    void generatesVersion7AndRfcVariant() {
        UUID u = Uuid7.generate();
        assertThat(u.version()).isEqualTo(7);
        assertThat(u.variant()).isEqualTo(2); // RFC 4122/9562 变体（10xx）
    }

    @Test
    void generatesUniqueValues() {
        Set<UUID> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            assertThat(seen.add(Uuid7.generate())).isTrue();
        }
    }

    @Test
    void isTimeOrderedAcrossMillis() throws InterruptedException {
        UUID earlier = Uuid7.generate();
        Thread.sleep(2);
        UUID later = Uuid7.generate();
        // 高 48 位为毫秒时间戳，跨毫秒后整体单调递增（无符号字典序，等价于 toString 比较）
        assertThat(earlier.toString().compareTo(later.toString())).isLessThan(0);
    }
}
