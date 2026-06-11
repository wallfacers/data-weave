package com.dataweave.worker.application;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Worker 进程启动纪元号管理器（design D7 / task 3.5）。
 *
 * <p>每次进程启动生成一个单调递增的 incarnation 值，随心跳上报给 master。
 * Master 检测到 incarnation 变化时判定 worker 已重启，将旧 incarnation 下的全部
 * 运行中实例标记 FAILED（WORKER_RESTART）。
 *
 * <p>incarnation 取 {@code System.currentTimeMillis() / 1000}（秒级时间戳），
 * 不同启动间隔足够大，且跨 worker 不重复（因 worker_node_code 已区分节点）。
 */
@Component
public class IncarnationManager {

    private final AtomicLong incarnation;

    public IncarnationManager() {
        this.incarnation = new AtomicLong(System.currentTimeMillis() / 1000);
    }

    /** 当前 incarnation 值。 */
    public long incarnation() {
        return incarnation.get();
    }

    /** 手动递增（用于测试）。 */
    public long increment() {
        return incarnation.incrementAndGet();
    }
}
