package com.dataweave.master.domain;

import java.util.function.Consumer;

/**
 * 事件总线接缝（design D2/D10）：跨 master 唤醒与状态事件广播。
 *
 * <p>分工纲领「DB 保正确性，Redis 保实时性」中的实时侧：发布即触发一轮调度（毫秒级快路径）。
 * all-in-one 默认 {@code memory} 实现（进程内直达）；distributed 用 Redis pub/sub。
 * 总线全丢时系统退化为兜底轮询，正确性无损。
 */
public interface EventBus {

    /** 发布一条消息到指定频道（如唤醒频道 {@code dw:wake}、状态事件 {@code dw:evt:{id}}）。 */
    void publish(String channel, String message);

    /** 订阅频道；返回可关闭的订阅句柄。 */
    Subscription subscribe(String channel, Consumer<String> handler);

    interface Subscription extends AutoCloseable {
        @Override
        void close();
    }
}
