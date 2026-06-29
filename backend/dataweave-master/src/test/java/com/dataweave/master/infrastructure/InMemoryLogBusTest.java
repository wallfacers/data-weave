package com.dataweave.master.infrastructure;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link InMemoryLogBus#totalBacklog()} 求和与单实例行数上限约束。 */
class InMemoryLogBusTest {

    @Test
    void totalBacklog_跨实例求和() {
        InMemoryLogBus bus = new InMemoryLogBus();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        bus.append(a, "a1");
        bus.append(a, "a2");
        bus.append(b, "b1");
        assertThat(bus.totalBacklog()).isEqualTo(3);
    }

    @Test
    void totalBacklog_空总线为零() {
        assertThat(new InMemoryLogBus().totalBacklog()).isZero();
    }

    @Test
    void totalBacklog_受单实例行数上限约束() {
        InMemoryLogBus bus = new InMemoryLogBus();
        UUID id = UUID.randomUUID();
        // InMemoryLogBus 单实例上限 5000 行；append 超量后只保留尾部，totalBacklog 应封顶 5000。
        for (int i = 0; i < 5005; i++) {
            bus.append(id, "line-" + i);
        }
        assertThat(bus.totalBacklog()).isEqualTo(5000);
    }

    @Test
    void totalBacklog_读不改变积压() {
        InMemoryLogBus bus = new InMemoryLogBus();
        UUID id = UUID.randomUUID();
        bus.append(id, "x");
        bus.read(id, null, 100);
        assertThat(bus.totalBacklog()).isEqualTo(1);
    }
}
