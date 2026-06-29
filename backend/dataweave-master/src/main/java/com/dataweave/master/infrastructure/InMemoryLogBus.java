package com.dataweave.master.infrastructure;

import com.dataweave.master.domain.LogBus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link LogBus} 的内存实现（all-in-one 默认）：每实例一条按追加序号编址的行列表。
 *
 * <p>{@code afterId} 为自增序号字符串，支持 SSE Last-Event-ID 续传。distributed 模式由 Redis Stream
 * 实现替换（{@code logbus.type=redis}）。内存实现按实例保留近 {@code maxLinesPerInstance} 行防爆。
 */
@Component
@ConditionalOnProperty(name = "logbus.type", havingValue = "memory", matchIfMissing = true)
public class InMemoryLogBus implements LogBus {

    private static final int MAX_LINES_PER_INSTANCE = 5000;

    private final Map<UUID, Stream> streams = new ConcurrentHashMap<>();

    @Override
    public void append(UUID instanceId, String line) {
        Stream s = streams.computeIfAbsent(instanceId, k -> new Stream());
        synchronized (s) {
            long id = ++s.lastSeq;
            s.entries.add(new Entry(Long.toString(id), line));
            // 防爆：仅保留尾部
            while (s.entries.size() > MAX_LINES_PER_INSTANCE) {
                s.entries.remove(0);
            }
        }
    }

    @Override
    public List<Entry> read(UUID instanceId, String afterId, int limit) {
        Stream s = streams.get(instanceId);
        if (s == null) {
            return List.of();
        }
        long after = parse(afterId);
        List<Entry> out = new ArrayList<>();
        synchronized (s) {
            for (Entry e : s.entries) {
                if (Long.parseLong(e.id()) > after) {
                    out.add(e);
                    if (out.size() >= limit) {
                        break;
                    }
                }
            }
        }
        return out;
    }

    @Override
    public long totalBacklog() {
        long sum = 0;
        // 遍历弱一致快照，逐 Stream 取 size（与 append/read 同锁，读一致）。
        for (Stream s : streams.values()) {
            synchronized (s) {
                sum += s.entries.size();
            }
        }
        return sum;
    }

    private long parse(String afterId) {
        if (afterId == null || afterId.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(afterId.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static final class Stream {
        private final List<Entry> entries = new ArrayList<>();
        private long lastSeq = 0L;
    }
}
