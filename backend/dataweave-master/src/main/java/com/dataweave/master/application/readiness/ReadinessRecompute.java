package com.dataweave.master.application.readiness;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 051 就绪态物化：scoped 重算核心（幂等，纯读权威态）。
 *
 * <p>给定满足方实例 U 或直接给定下游集 D，按权威状态重算每个下游的 unmet_deps。
 * 语义复用 049 batchUpstreamReady（STRONG/WEAK）与 048 batchCrossCycleReady（首周期豁免），
 * 作用域限 D（非全体候选），供 Maintainer/Initializer/Reconciler 复用。
 *
 * <p>暴露 {@link #recomputeSingle(UUID)} 单实例重算入口供 Initializer 在物化时算初值（C1：权威重算，只计未满足依赖）。
 */
@Service
public class ReadinessRecompute {

    private static final Logger log = LoggerFactory.getLogger(ReadinessRecompute.class);

    private final JdbcTemplate jdbc;

    public ReadinessRecompute(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 给定终端实例 U 的 id，解析受影响的 WAITING 下游集 D，对 D 重算 unmet_deps。
     *
     * @return 重算后的 (instanceId -> newUnmetDeps) 映射
     */
    public Map<UUID, Integer> recomputeFromTerminal(UUID upstreamInstanceId) {
        // 读取 U 的快照信息
        List<UpstreamInfo> infos = jdbc.query(
                "SELECT ti.workflow_instance_id, ti.workflow_node_id, ti.biz_date, " +
                "ti.tenant_id, ti.project_id, " +
                "(SELECT wi.workflow_id FROM workflow_instance wi WHERE wi.id = ti.workflow_instance_id) AS wf_id " +
                "FROM task_instance ti WHERE ti.id = ? AND ti.deleted = 0",
                (rs, n) -> new UpstreamInfo(
                        rs.getObject("workflow_instance_id", UUID.class),
                        (Long) rs.getObject("workflow_node_id"),
                        rs.getString("biz_date"),
                        (Long) rs.getObject("wf_id"),
                        (Long) rs.getObject("tenant_id"),
                        (Long) rs.getObject("project_id")),
                upstreamInstanceId);
        if (infos.isEmpty()) {
            return Collections.emptyMap();
        }
        UpstreamInfo info = infos.get(0);
        if (info.workflowNodeId == null) {
            return Collections.emptyMap();
        }

        // 解析下游集 D
        Set<UUID> downstreamIds = resolveDownstream(info);
        if (downstreamIds.isEmpty()) {
            return Collections.emptyMap();
        }

        // 批量重算
        return recompute(new ArrayList<>(downstreamIds));
    }

    /**
     * 对一批 task_instance 重算 unmet_deps（批量，权威读）。
     * 供 Reconciler 直接传入实例 id 列表。
     *
     * @return (instanceId -> newUnmetDeps) 映射
     */
    public Map<UUID, Integer> recompute(List<UUID> instanceIds) {
        if (instanceIds == null || instanceIds.isEmpty()) {
            return Collections.emptyMap();
        }

        // ① 批量读取这些实例的关键信息
        Map<UUID, DownstreamInfo> infos = batchLoadDownstreamInfo(instanceIds);
        if (infos.isEmpty()) {
            return Collections.emptyMap();
        }

        // ② 批量重算上游 edge 就绪
        Map<UUID, Integer> upstreamUnmet = batchUpstreamUnmet(infos);

        // ③ 批量重算跨周期就绪
        Map<UUID, Integer> crossCycleUnmet = batchCrossCycleUnmet(infos);

        // ④ 合并
        Map<UUID, Integer> result = new LinkedHashMap<>();
        for (UUID id : infos.keySet()) {
            int unmet = upstreamUnmet.getOrDefault(id, 0) + crossCycleUnmet.getOrDefault(id, 0);
            result.put(id, Math.max(0, unmet));
        }
        return result;
    }

    /**
     * 单实例重算入口（供 ReadinessInitializer 物化时算初值）。
     * C1 关键：只计未满足依赖，不是依赖总数。
     */
    public int recomputeSingle(UUID instanceId) {
        Map<UUID, Integer> result = recompute(List.of(instanceId));
        return result.getOrDefault(instanceId, 0);
    }

    // ─── D 解析 ────────────────────────────────────────────────

    /**
     * 给定终端实例信息，解析受影响的 WAITING 下游集 D。
     * 包含：同 DAG 后继 + 跨周期后继。
     */
    private Set<UUID> resolveDownstream(UpstreamInfo info) {
        Set<UUID> result = new LinkedHashSet<>();

        // 同 DAG 后继：workflow_edge WHERE from_node_id = N → 同 workflow_instance 的 WAITING 实例
        if (info.workflowInstanceId != null && info.workflowNodeId != null) {
            List<UUID> sameDag = jdbc.query(
                    "SELECT ti.id FROM task_instance ti " +
                    "INNER JOIN workflow_edge we ON we.to_node_id = ti.workflow_node_id AND we.deleted = 0 " +
                    "WHERE we.from_node_id = ? " +
                    "AND ti.workflow_instance_id = ? " +
                    "AND ti.state = 'WAITING' AND ti.deleted = 0",
                    (rs, n) -> rs.getObject("id", UUID.class),
                    info.workflowNodeId, info.workflowInstanceId);
            result.addAll(sameDag);
        }

        // 跨周期后继：workflow_dependency WHERE depend_node_id = N AND enabled=1
        if (info.workflowNodeId != null && info.bizDate != null) {
            List<CrossCycleDep> deps = jdbc.query(
                    "SELECT workflow_id, node_id, depend_node_id, date_offset, earliest_biz_date " +
                    "FROM workflow_dependency WHERE depend_node_id = ? AND enabled = 1 AND deleted = 0",
                    (rs, n) -> new CrossCycleDep(
                            (Long) rs.getObject("workflow_id"),
                            (Long) rs.getObject("node_id"),
                            (Long) rs.getObject("depend_node_id"),
                            rs.getString("date_offset"),
                            rs.getString("earliest_biz_date")),
                    info.workflowNodeId);
            for (CrossCycleDep dep : deps) {
                // 逆偏移：从 B 算后继的 bizDate B'（U1：正向枚举，不假定可逆）
                List<String> candidateBizDates = reverseOffsetBizDate(info.bizDate, dep.dateOffset());
                for (String candidateBiz : candidateBizDates) {
                    List<UUID> ids = jdbc.query(
                            "SELECT ti.id FROM task_instance ti " +
                            "INNER JOIN workflow_instance wi ON wi.id = ti.workflow_instance_id " +
                            "WHERE ti.workflow_node_id = ? AND ti.biz_date = ? " +
                            "AND ti.state = 'WAITING' AND ti.deleted = 0 " +
                            "AND wi.workflow_id = ?",
                            (rs, n) -> rs.getObject("id", UUID.class),
                            dep.nodeId(), candidateBiz, dep.workflowId());
                    result.addAll(ids);
                }
            }
        }

        return result;
    }

    // ─── 上游 edge 就绪重算 ─────────────────────────────────────

    /**
     * 对一批下游实例，批量计算各自未满足的上游 edge 数。
     * 复用 049 batchUpstreamReady 的 STRONG/WEAK 语义。
     */
    private Map<UUID, Integer> batchUpstreamUnmet(Map<UUID, DownstreamInfo> infos) {
        Map<UUID, Integer> unmet = new HashMap<>();

        // 收集需检查的实例（有 workflow_node_id 和 workflow_instance_id 的）
        List<UUID> toCheck = new ArrayList<>();
        for (var entry : infos.entrySet()) {
            DownstreamInfo info = entry.getValue();
            if (info.workflowInstanceId == null || info.workflowNodeId == null) {
                unmet.put(entry.getKey(), 0); // 单跑实例：无上游门
            } else {
                toCheck.add(entry.getKey());
            }
        }
        if (toCheck.isEmpty()) return unmet;

        // ① 批量查 workflow_edge：to_node_id IN (candidates)
        Set<Long> toNodeIds = new LinkedHashSet<>();
        for (UUID id : toCheck) {
            DownstreamInfo info = infos.get(id);
            if (info.workflowNodeId != null) toNodeIds.add(info.workflowNodeId);
        }
        Map<Long, List<EdgeInfo>> edgesByTo = batchLoadEdges(toNodeIds);

        // ② 收集所有 pred (workflowInstanceId, fromNodeId) → 批量查 state
        Map<String, Set<String>> predStates = batchLoadPredStates(toCheck, infos, edgesByTo);

        // ③ 逐实例判定
        for (UUID id : toCheck) {
            DownstreamInfo info = infos.get(id);
            List<EdgeInfo> edges = edgesByTo.get(info.workflowNodeId);
            if (edges == null || edges.isEmpty()) {
                unmet.put(id, 0); // 无上游 edge
                continue;
            }
            int unsatisfied = 0;
            for (EdgeInfo edge : edges) {
                Set<String> states = predStates.get(info.workflowInstanceId + ":" + edge.fromNodeId());
                boolean weak = "WEAK".equals(edge.strength());
                boolean satisfied = states != null && (states.contains("SUCCESS")
                        || (weak && states.contains("FAILED")));
                if (!satisfied) unsatisfied++;
            }
            unmet.put(id, unsatisfied);
        }

        // 补回不在 toCheck 中的（理论上都已处理）
        for (UUID id : infos.keySet()) {
            unmet.putIfAbsent(id, 0);
        }
        return unmet;
    }

    // ─── 跨周期就绪重算 ────────────────────────────────────────

    /**
     * 对一批下游实例，批量计算各自未满足的跨周期依赖数。
     * 复用 048 batchCrossCycleReady 的首周期豁免语义。
     */
    private Map<UUID, Integer> batchCrossCycleUnmet(Map<UUID, DownstreamInfo> infos) {
        Map<UUID, Integer> unmet = new HashMap<>();

        // 非 CRON 实例：跨周期计 0（直通）
        List<UUID> cronIds = new ArrayList<>();
        for (var entry : infos.entrySet()) {
            DownstreamInfo info = entry.getValue();
            if (!"CRON".equals(info.triggerType)
                    || info.workflowInstanceId == null || info.workflowNodeId == null
                    || info.bizDate == null || info.workflowId == null) {
                unmet.put(entry.getKey(), 0);
            } else {
                cronIds.add(entry.getKey());
            }
        }
        if (cronIds.isEmpty()) return unmet;

        // ① 按 (workflowId, nodeId) 批量查 workflow_dependency
        Map<String, List<CrossCycleDep>> depsByKey = batchLoadDependencies(infos, cronIds);

        // ② 收集所有 (dependNodeId, prevBizDate) → 批量 COUNT SUCCESS
        LinkedHashSet<String> countKeys = new LinkedHashSet<>();
        Map<String, CrossCycleDep> keyToDep = new HashMap<>();
        for (UUID id : cronIds) {
            DownstreamInfo info = infos.get(id);
            List<CrossCycleDep> deps = depsByKey.get(info.workflowId + ":" + info.workflowNodeId);
            if (deps == null) {
                unmet.put(id, 0); // 无跨周期依赖
                continue;
            }
            int unsatisfied = 0;
            for (CrossCycleDep dep : deps) {
                if (dep.earliestBizDate() != null && info.bizDate.compareTo(dep.earliestBizDate()) < 0) {
                    continue; // 首周期豁免
                }
                // 前向偏移：offsetBizDate(B', offset) = 上周期 bizDate
                String prevBizDate = offsetBizDate(info.bizDate, dep.dateOffset());
                String ck = dep.dependNodeId() + ":" + prevBizDate;
                countKeys.add(ck);
                keyToDep.put(ck, dep);
                unsatisfied++; // 先假设不满足，COUNT 结果出来后抵扣
            }
        }

        // 批量 COUNT SUCCESS
        Map<String, Integer> successCounts = batchCountSuccess(countKeys);

        // ③ 逐实例判定
        for (UUID id : cronIds) {
            DownstreamInfo info = infos.get(id);
            List<CrossCycleDep> deps = depsByKey.get(info.workflowId + ":" + info.workflowNodeId);
            if (deps == null) {
                unmet.putIfAbsent(id, 0);
                continue;
            }
            int unsatisfied = 0;
            for (CrossCycleDep dep : deps) {
                if (dep.earliestBizDate() != null && info.bizDate.compareTo(dep.earliestBizDate()) < 0) {
                    continue; // 首周期豁免
                }
                boolean satisfied = false;
                String prevBizDate = offsetBizDate(info.bizDate, dep.dateOffset());
                Integer cnt = successCounts.get(dep.dependNodeId() + ":" + prevBizDate);
                if (cnt != null && cnt > 0) {
                    satisfied = true;
                }
                if (!satisfied) unsatisfied++;
            }
            unmet.put(id, unsatisfied);
        }

        for (UUID id : infos.keySet()) {
            unmet.putIfAbsent(id, 0);
        }
        return unmet;
    }

    // ─── 批量加载辅助 ───────────────────────────────────────────

    private Map<UUID, DownstreamInfo> batchLoadDownstreamInfo(List<UUID> ids) {
        if (ids.isEmpty()) return Collections.emptyMap();
        StringBuilder in = new StringBuilder();
        List<Object> args = new ArrayList<>();
        int i = 0;
        for (UUID id : ids) {
            if (i++ > 0) in.append(',');
            in.append('?');
            args.add(id);
        }
        return jdbc.query(
                "SELECT ti.id, ti.workflow_instance_id, ti.workflow_node_id, ti.biz_date, " +
                "ti.locale, " +
                "(SELECT wi.trigger_type FROM workflow_instance wi WHERE wi.id = ti.workflow_instance_id) AS trigger_type, " +
                "(SELECT wi.workflow_id FROM workflow_instance wi WHERE wi.id = ti.workflow_instance_id) AS wf_id " +
                "FROM task_instance ti WHERE ti.id IN (" + in + ") AND ti.deleted = 0",
                (rs) -> {
                    Map<UUID, DownstreamInfo> m = new LinkedHashMap<>();
                    while (rs.next()) {
                        UUID id = rs.getObject("id", UUID.class);
                        m.put(id, new DownstreamInfo(
                                rs.getObject("workflow_instance_id", UUID.class),
                                (Long) rs.getObject("workflow_node_id"),
                                rs.getString("biz_date"),
                                (Long) rs.getObject("wf_id"),
                                rs.getString("trigger_type")));
                    }
                    return m;
                }, args.toArray());
    }

    private Map<Long, List<EdgeInfo>> batchLoadEdges(Set<Long> toNodeIds) {
        if (toNodeIds.isEmpty()) return Collections.emptyMap();
        StringBuilder in = new StringBuilder();
        int i = 0;
        for (Long id : toNodeIds) {
            if (i++ > 0) in.append(',');
            in.append('?');
        }
        return jdbc.query(
                "SELECT to_node_id, from_node_id, strength FROM workflow_edge " +
                "WHERE deleted = 0 AND to_node_id IN (" + in + ")",
                (rs) -> {
                    Map<Long, List<EdgeInfo>> m = new HashMap<>();
                    while (rs.next()) {
                        m.computeIfAbsent(rs.getLong("to_node_id"), k -> new ArrayList<>())
                                .add(new EdgeInfo(rs.getLong("from_node_id"), rs.getString("strength")));
                    }
                    return m;
                }, toNodeIds.toArray());
    }

    private Map<String, Set<String>> batchLoadPredStates(List<UUID> toCheck,
                                                          Map<UUID, DownstreamInfo> infos,
                                                          Map<Long, List<EdgeInfo>> edgesByTo) {
        LinkedHashSet<String> predKeys = new LinkedHashSet<>();
        Map<String, UUID> predKeyToWi = new HashMap<>();
        Map<String, Long> predKeyToNode = new HashMap<>();
        for (UUID id : toCheck) {
            DownstreamInfo info = infos.get(id);
            List<EdgeInfo> edges = edgesByTo.get(info.workflowNodeId);
            if (edges == null) continue;
            for (EdgeInfo edge : edges) {
                String key = info.workflowInstanceId + ":" + edge.fromNodeId();
                predKeys.add(key);
                predKeyToWi.put(key, info.workflowInstanceId);
                predKeyToNode.put(key, edge.fromNodeId());
            }
        }
        if (predKeys.isEmpty()) return Collections.emptyMap();

        StringBuilder in = new StringBuilder();
        List<Object> args = new ArrayList<>();
        int i = 0;
        for (String key : predKeys) {
            if (i++ > 0) in.append(',');
            in.append("(?,?)");
            args.add(predKeyToWi.get(key));
            args.add(predKeyToNode.get(key));
        }
        return jdbc.query(
                "SELECT workflow_instance_id, workflow_node_id, state FROM task_instance " +
                "WHERE deleted = 0 AND state IN ('SUCCESS','FAILED') " +
                "AND (workflow_instance_id, workflow_node_id) IN (" + in + ")",
                (rs) -> {
                    Map<String, Set<String>> m = new HashMap<>();
                    while (rs.next()) {
                        m.computeIfAbsent(
                                rs.getObject("workflow_instance_id") + ":" + rs.getLong("workflow_node_id"),
                                k -> new HashSet<>()).add(rs.getString("state"));
                    }
                    return m;
                }, args.toArray());
    }

    private Map<String, List<CrossCycleDep>> batchLoadDependencies(Map<UUID, DownstreamInfo> infos,
                                                                    List<UUID> cronIds) {
        // 按 (workflowId, nodeId) 去重
        LinkedHashMap<String, long[]> wfNode = new LinkedHashMap<>();
        for (UUID id : cronIds) {
            DownstreamInfo info = infos.get(id);
            wfNode.putIfAbsent(info.workflowId + ":" + info.workflowNodeId,
                    new long[]{info.workflowId, info.workflowNodeId});
        }
        StringBuilder in = new StringBuilder();
        List<Object> args = new ArrayList<>();
        int i = 0;
        for (String key : wfNode.keySet()) {
            if (i++ > 0) in.append(',');
            in.append("(?,?)");
            args.add(wfNode.get(key)[0]);
            args.add(wfNode.get(key)[1]);
        }

        // 注意：这里 node_id 是下游节点、depend_node_id 是上游节点
        return jdbc.query(
                "SELECT workflow_id, node_id, depend_node_id, date_offset, earliest_biz_date " +
                "FROM workflow_dependency WHERE enabled = 1 AND deleted = 0 " +
                "AND earliest_biz_date IS NOT NULL " +
                "AND (workflow_id, node_id) IN (" + in + ")",
                (rs) -> {
                    Map<String, List<CrossCycleDep>> m = new HashMap<>();
                    while (rs.next()) {
                        String key = rs.getLong("workflow_id") + ":" + rs.getLong("node_id");
                        m.computeIfAbsent(key, k -> new ArrayList<>()).add(new CrossCycleDep(
                                (Long) rs.getObject("workflow_id"),
                                (Long) rs.getObject("node_id"),
                                (Long) rs.getObject("depend_node_id"),
                                rs.getString("date_offset"),
                                rs.getString("earliest_biz_date")));
                    }
                    return m;
                }, args.toArray());
    }

    private Map<String, Integer> batchCountSuccess(LinkedHashSet<String> countKeys) {
        if (countKeys.isEmpty()) return Collections.emptyMap();
        StringBuilder in = new StringBuilder();
        List<Object> args = new ArrayList<>();
        int i = 0;
        for (String ck : countKeys) {
            String[] parts = ck.split(":", 2);
            if (i++ > 0) in.append(',');
            in.append("(?,?)");
            args.add(Long.parseLong(parts[0]));
            args.add(parts[1]);
        }
        return jdbc.query(
                "SELECT workflow_node_id, biz_date, COUNT(*) FROM task_instance " +
                "WHERE state = 'SUCCESS' AND deleted = 0 " +
                "AND (workflow_node_id, biz_date) IN (" + in + ") " +
                "GROUP BY workflow_node_id, biz_date",
                (rs) -> {
                    Map<String, Integer> m = new HashMap<>();
                    while (rs.next()) {
                        m.put(rs.getLong("workflow_node_id") + ":" + rs.getString("biz_date"), rs.getInt(3));
                    }
                    return m;
                }, args.toArray());
    }

    // ─── 跨周期 bizDate 偏移 ──────────────────────────────────

    /**
     * 前向偏移（与 SchedulerKernel.offsetBizDate 同）。
     * LAST_DAY → 上一日(-1)，CURRENT_DAY/空 → 同日。
     * 用于：给定下游 bizDate B'，计算上周期 bizDate = offsetBizDate(B', offset)。
     */
    static String offsetBizDate(String bizDate, String offset) {
        if (bizDate == null) return null;
        if ("LAST_DAY".equals(offset)) {
            try {
                return java.time.LocalDate.parse(bizDate).minusDays(1).toString();
            } catch (Exception e) {
                return bizDate;
            }
        }
        return bizDate;
    }

    /**
     * 逆偏移：从上游 bizDate B 和 date_offset 反推下游 bizDate B'（U1：正向枚举，不假定 offset 可逆）。
     * <p>用于 D 解析——给定满足方实例 bizDate B，求下游后继的 bizDate B' 使 offsetBizDate(B', offset) == B。
     * LAST_DAY 下 offsetBizDate(B', LAST_DAY) = B' - 1day，所以 B' = B + 1day。
     */
    static List<String> reverseOffsetBizDate(String downstreamBizDate, String offset) {
        if (downstreamBizDate == null) return Collections.emptyList();
        List<String> candidates = new ArrayList<>();
        try {
            LocalDate d = LocalDate.parse(downstreamBizDate, DateTimeFormatter.ISO_LOCAL_DATE);
            if ("LAST_DAY".equals(offset)) {
                // offsetBizDate(X, LAST_DAY) = X - 1day
                // So B' satisfies offsetBizDate(B', LAST_DAY) = B means B' - 1day = B, so B' = B + 1day
                candidates.add(d.plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE));
            } else {
                // CURRENT_DAY / null / 空 → 同日
                candidates.add(d.format(DateTimeFormatter.ISO_LOCAL_DATE));
            }
        } catch (Exception e) {
            // 非标准日期格式，保守返回自身
            candidates.add(downstreamBizDate);
        }
        return candidates;
    }

    // ─── 内部类型 ───────────────────────────────────────────────

    private record UpstreamInfo(UUID workflowInstanceId, Long workflowNodeId, String bizDate,
                                 Long workflowId, Long tenantId, Long projectId) {}

    record DownstreamInfo(UUID workflowInstanceId, Long workflowNodeId, String bizDate,
                          Long workflowId, String triggerType) {}

    private record EdgeInfo(Long fromNodeId, String strength) {}

    /**
     * 跨周期依赖信息。
     * dependNodeId = 被依赖的上游节点，dateOffset = date_offset，earliestBizDate = 首周期豁免阈值
     */
    record CrossCycleDep(Long workflowId, Long nodeId, Long dependNodeId, String dateOffset,
                          String earliestBizDate) {}
}
