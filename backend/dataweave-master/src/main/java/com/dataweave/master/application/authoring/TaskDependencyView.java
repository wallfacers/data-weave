package com.dataweave.master.application.authoring;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 058 任务依赖视图（US1 / FR-006）：某任务的上/下游依赖，声明（{@code workflow_edge}）与
 * 推导（血缘）合并后带 origin 归属，供 agent 一眼看清链路及一致性。
 *
 * <p>合并规则（{@link #mergeDirection}）确定性、无 LLM：以 {@code (from,to)} 为键去重，
 * 两通道同现 → {@code BOTH} 且取更短跳距；仅一侧 → 保留该侧 origin。稳定顺序：声明优先、
 * 再按跳距/键补入推导独有边——保证同输入同输出（SC 可复现）。
 *
 * @param taskRef    主任务标识
 * @param upstream   上游依赖边（fromTaskRef=上游）
 * @param downstream 下游依赖边（toTaskRef=下游）
 */
public record TaskDependencyView(String taskRef, List<DependencyEdge> upstream, List<DependencyEdge> downstream) {

    /** 合并声明 + 推导双通道为一个依赖视图（纯函数入口，可脱离服务单测）。 */
    public static TaskDependencyView merge(String taskRef,
                                           List<DependencyEdge> declaredUpstream,
                                           List<DependencyEdge> derivedUpstream,
                                           List<DependencyEdge> declaredDownstream,
                                           List<DependencyEdge> derivedDownstream) {
        return new TaskDependencyView(taskRef,
                mergeDirection(declaredUpstream, derivedUpstream),
                mergeDirection(declaredDownstream, derivedDownstream));
    }

    /** 同方向两通道合并：声明先入序，推导补入；同键升 BOTH 取更短跳距。 */
    private static List<DependencyEdge> mergeDirection(List<DependencyEdge> declared, List<DependencyEdge> derived) {
        LinkedHashMap<String, DependencyEdge> byKey = new LinkedHashMap<>();
        if (declared != null) {
            for (DependencyEdge e : declared) {
                if (e == null) continue;
                byKey.put(key(e), new DependencyEdge(e.fromTaskRef(), e.toTaskRef(), e.hop(), DependencyEdge.DECLARED));
            }
        }
        if (derived != null) {
            for (DependencyEdge e : derived) {
                if (e == null) continue;
                String k = key(e);
                DependencyEdge prior = byKey.get(k);
                if (prior == null) {
                    byKey.put(k, new DependencyEdge(e.fromTaskRef(), e.toTaskRef(), e.hop(), DependencyEdge.DERIVED));
                } else {
                    byKey.put(k, new DependencyEdge(prior.fromTaskRef(), prior.toTaskRef(),
                            Math.min(prior.hop(), e.hop()), DependencyEdge.BOTH));
                }
            }
        }
        return new ArrayList<>(byKey.values());
    }

    private static String key(DependencyEdge e) {
        return e.fromTaskRef() + "" + e.toTaskRef();
    }
}
