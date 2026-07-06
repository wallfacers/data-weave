package com.dataweave.master.infrastructure;

import java.util.List;

/**
 * 051 就绪态物化：readiness_signal 表的持久化接口。
 *
 * <p>写入：完成/reset 事务内 INSERT 一行（与 casTaskTerminal 同事务，no-loss 构造保证）。
 * <p>消费：Maintainer 用 FOR UPDATE SKIP LOCKED 批量领取未处理信号，处理后标记 processed=1。
 */
public interface ReadinessSignalRepository {

    /**
     * 插入一条就绪信号（TERMINAL 或 RESET）。
     *
     * @return 新生成的 id（GeneratedKeyHolder 取自增主键）
     */
    long insert(ReadinessSignalRow row);

    /**
     * 批量领取未处理信号（FOR UPDATE SKIP LOCKED），按 id 升序，至多 {@code limit} 条。
     */
    List<ReadinessSignalRow> pollPending(int limit);

    /**
     * 批量标记信号为已处理。
     */
    void markProcessed(List<Long> ids);
}
