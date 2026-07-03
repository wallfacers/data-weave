package com.dataweave.master.domain.incident;

import java.util.Map;
import java.util.Set;

/**
 * Incident 工单状态常量 + 合法迁移表（043）。
 *
 * <p>一切状态推进乐观 CAS（{@code UPDATE ... WHERE state=?}），多 master 安全，复用调度内核不变量②。
 * CLOSED 为唯一终态；同签名新故障在 CLOSED 后开新单（active_key 唯一约束不冲突）。
 */
public final class IncidentStates {

    private IncidentStates() {}

    public static final String OPEN = "OPEN";
    public static final String MITIGATING = "MITIGATING";
    public static final String RESOLVED = "RESOLVED";
    public static final String SUPPRESSED = "SUPPRESSED";
    public static final String CLOSED = "CLOSED";

    /** 活跃态：OPEN + MITIGATING（默认队列可见）。 */
    public static final Set<String> ACTIVE = Set.of(OPEN, MITIGATING);

    /** 合法状态迁移：from → to 集合。不在集合内的迁移 CAS 应失败。 */
    public static final Map<String, Set<String>> TRANSITIONS = Map.of(
            OPEN,        Set.of(MITIGATING, RESOLVED, SUPPRESSED),
            MITIGATING,  Set.of(OPEN, RESOLVED, SUPPRESSED),
            RESOLVED,    Set.of(OPEN, CLOSED),
            SUPPRESSED,  Set.of(OPEN),
            CLOSED,      Set.of()
    );

    /** 合法迁移检查。 */
    public static boolean isValid(String from, String to) {
        Set<String> allowed = TRANSITIONS.get(from);
        return allowed != null && allowed.contains(to);
    }

    public static boolean isTerminal(String state) {
        return CLOSED.equals(state);
    }
}
