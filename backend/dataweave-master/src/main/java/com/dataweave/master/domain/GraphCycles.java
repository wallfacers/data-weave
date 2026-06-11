package com.dataweave.master.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 有向图环检测（design D3 死锁防御：发布时 DAG 拓扑环检测、跨流依赖全局环检测）。
 *
 * <p>纯函数、无副作用：以邻接表表示有向图，DFS 寻找回边。命中即返回构成环的节点路径
 * （首尾为同一节点，便于提示）。任务级「等待不占资源」配合本检测构成任务侧三防御。
 */
public final class GraphCycles {

    private GraphCycles() {
    }

    /**
     * 在有向图中寻找一个环。
     *
     * @param adjacency 邻接表：节点 → 其直接后继集合
     * @return 若有环，返回环路径（如 A→B→C→A，首尾相同）；无环返回空
     */
    public static <T> Optional<List<T>> findCycle(Map<T, ? extends Collection<T>> adjacency) {
        // 收集全部节点（含只作为后继出现的）
        Set<T> nodes = new LinkedHashSet<>(adjacency.keySet());
        for (Collection<T> succ : adjacency.values()) {
            nodes.addAll(succ);
        }
        Set<T> visited = new HashSet<>();
        for (T n : nodes) {
            if (!visited.contains(n)) {
                List<T> path = new ArrayList<>();
                Set<T> onPath = new LinkedHashSet<>();
                List<T> cycle = dfs(n, adjacency, visited, onPath, path);
                if (cycle != null) {
                    return Optional.of(cycle);
                }
            }
        }
        return Optional.empty();
    }

    private static <T> List<T> dfs(T node, Map<T, ? extends Collection<T>> adjacency,
                                   Set<T> visited, Set<T> onPath, List<T> path) {
        visited.add(node);
        onPath.add(node);
        path.add(node);
        Collection<T> successors = adjacency.get(node);
        if (successors != null) {
            for (T next : successors) {
                if (onPath.contains(next)) {
                    // 回边 → 环：截取 next..node 段并闭合
                    int idx = path.indexOf(next);
                    List<T> cycle = new ArrayList<>(path.subList(idx, path.size()));
                    cycle.add(next);
                    return cycle;
                }
                if (!visited.contains(next)) {
                    List<T> cycle = dfs(next, adjacency, visited, onPath, path);
                    if (cycle != null) {
                        return cycle;
                    }
                }
            }
        }
        onPath.remove(node);
        path.remove(path.size() - 1);
        return null;
    }
}
