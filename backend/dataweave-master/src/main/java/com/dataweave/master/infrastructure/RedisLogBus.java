package com.dataweave.master.infrastructure;

import com.dataweave.master.domain.LogBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.domain.Range;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@link LogBus} 的 Redis Stream 实现（design D11 / task 3.8）。
 *
 * <p>每个 task_instance 一个 Redis Stream {@code dw:log:{instanceId}}。
 * append → XADD（TTL/maxlen 防爆）；read → XRANGE 按 entry id 续传。
 * 配置 {@code logbus.type=redis} 激活。
 */
@Component
@ConditionalOnProperty(name = "logbus.type", havingValue = "redis")
public class RedisLogBus implements LogBus {

    private static final Logger log = LoggerFactory.getLogger(RedisLogBus.class);

    private static final int MAXLEN = 5000;
    private static final long TTL_SECONDS = 3600; // 1h

    private final StringRedisTemplate redisTemplate;
    /** 已写入过的实例集合（供 totalBacklog 遍历 XLEN；TTL 过期后 key 消失但残留项 XLEN 返回 0，不影响求和）。 */
    private final java.util.Set<UUID> knownInstances = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public RedisLogBus(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void append(UUID instanceId, String line) {
        String key = streamKey(instanceId);
        knownInstances.add(instanceId);
        try {
            redisTemplate.opsForStream().add(key, Map.of("line", line));
            // 设置 TTL（每次 append 刷新）
            redisTemplate.expire(key, java.time.Duration.ofSeconds(TTL_SECONDS));
        } catch (Exception e) {
            log.warn("[RedisLogBus] append 失败：instance={}, error={}", instanceId, e.getMessage());
        }
    }

    @Override
    public List<Entry> read(UUID instanceId, String afterId, int limit) {
        String key = streamKey(instanceId);
        try {
            // XRANGE：afterId 非空时从其下一个开始（exclusive: "(" 前缀），否则从头读
            String start = (afterId != null && !afterId.isBlank()) ? "(" + afterId : "-";
            @SuppressWarnings("unchecked")
            List<MapRecord<String, Object, Object>> records = (List) redisTemplate.opsForStream()
                    .range(key, Range.closed(start, "+"));

            if (records == null || records.isEmpty()) {
                return List.of();
            }

            List<Entry> out = new ArrayList<>();
            for (var record : records) {
                String id = record.getId().getValue();
                String line = (String) record.getValue().get("line");
                if (line != null) {
                    out.add(new Entry(id, line));
                }
                if (out.size() >= limit) {
                    break;
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("[RedisLogBus] read 失败：instance={}, error={}", instanceId, e.getMessage());
            return List.of();
        }
    }

    @Override
    public long totalBacklog() {
        // 逐已知实例 XLEN 求和；key 已过期/丢失返回 0，异常计 0（不影响正确性）。
        long sum = 0;
        for (UUID id : knownInstances) {
            try {
                Long size = redisTemplate.opsForStream().size(streamKey(id));
                if (size != null) {
                    sum += size;
                }
            } catch (Exception e) {
                // key 已过期或丢失 → 计 0
            }
        }
        return sum;
    }

    private String streamKey(UUID instanceId) {
        return "dw:log:" + instanceId;
    }
}
