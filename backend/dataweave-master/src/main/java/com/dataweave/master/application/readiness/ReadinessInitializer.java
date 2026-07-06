package com.dataweave.master.application.readiness;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 051 就绪态物化：物化时按权威状态算 unmet_deps 初值。
 *
 * <p>C1 关键：只计未满足依赖（复用 {@link ReadinessRecompute#recomputeSingle}），非朴素 edge/dep 计数。
 * 物化时上游/上周期实例可能已终态——跨周期上周期常已 SUCCESS；乱序/延迟物化下 DAG 内上游也可能已完成。
 * 已满足者不计入初值，否则无后续信号触发、实例卡 unmet>0 直到 Reconciler 兜底。
 */
@Service
public class ReadinessInitializer {

    private static final Logger log = LoggerFactory.getLogger(ReadinessInitializer.class);

    /**
     * 初值算失败时的 fail-closed 哨兵：置正值 → 实例判为「未就绪」，绝不提前认领下发。
     * Reconciler 的定向审计（WAITING + unmet_deps>0 + 停留超阈值）会重算权威值纠正。
     * 用 MAX_VALUE 而非 1：语义明确为「未知/不可信，待对账」，且不与真实依赖数混淆。
     */
    static final int UNMET_UNKNOWN = Integer.MAX_VALUE;

    private final ReadinessRecompute recompute;

    public ReadinessInitializer(ReadinessRecompute recompute) {
        this.recompute = recompute;
    }

    /**
     * 对一批新物化的 task_instance 计算 unmet_deps 初值。
     * 对每个实例调 ReadinessRecompute.recomputeSingle（权威重算，只计未满足）。
     *
     * @param instanceIds 新物化的 task_instance id 列表
     * @return (instanceId -> initialUnmetDeps) 映射
     */
    public java.util.Map<UUID, Integer> initialize(List<UUID> instanceIds) {
        java.util.Map<UUID, Integer> result = new java.util.LinkedHashMap<>();
        for (UUID id : instanceIds) {
            try {
                int unmet = recompute.recomputeSingle(id);
                result.put(id, unmet);
            } catch (Exception e) {
                // fail-closed：出错置正值哨兵 → 未就绪，绝不提前下发；Reconciler 权威纠正。
                log.warn("[ReadinessInit] 实例 {} 初值计算失败，fail-closed 置未就绪待对账：{}", id, e.getMessage());
                result.put(id, UNMET_UNKNOWN);
            }
        }
        log.debug("[ReadinessInit] 批量初始化 {} 实例", instanceIds.size());
        return result;
    }

    /**
     * 单实例初值计算。
     */
    public int initializeOne(UUID instanceId) {
        return recompute.recomputeSingle(instanceId);
    }
}
