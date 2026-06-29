package com.dataweave.master.domain;

import java.util.List;
import java.util.UUID;

/**
 * 实时日志管道接缝（design D11）：worker 按行/批 append，任一 master 读取转发 SSE。
 *
 * <p>每个 task_instance 一个逻辑流（天然多消费者、offset 续传）。all-in-one 默认 {@code memory} 实现；
 * distributed 用 Redis Stream（{@code dw:log:{instanceId}}，TTL/maxlen 防爆）。
 * 日志真相在 worker 本地落盘 + 归档（{@link LogArchiveStorage}），本总线只是实时管道。
 */
public interface LogBus {

    /** 追加一行日志到指定实例的流。 */
    void append(UUID instanceId, String line);

    /** 读取实例流中 {@code afterId} 之后的至多 {@code limit} 行（{@code afterId} 为 null 从头读）。 */
    List<Entry> read(UUID instanceId, String afterId, int limit);

    /**
     * 全总线当前积压行数总和（所有实例流的当前长度之和），供 {@code scheduler.log.stream.backlog} Gauge。
     * 实现应容忍并发修改与已过期实例，返回 0 而非抛异常。
     */
    long totalBacklog();

    /**
     * 一行日志及其流内偏移 id（用于 SSE Last-Event-ID 续传）。
     *
     * @param id   流内偏移标识（memory 实现为自增序号字符串；Redis 为 Stream entry id）
     * @param line 日志内容
     */
    record Entry(String id, String line) {
    }
}
