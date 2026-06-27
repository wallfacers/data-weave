package com.dataweave.master.filecontract.dto;

import java.util.List;

/**
 * File shape for {@code <slug>.flow.yaml} — workflow metadata + DAG (data-model §5).
 */
public record WorkflowDoc(
        int formatVersion,
        String name,
        String description,
        Schedule schedule,
        Integer priority,
        Boolean preemptible,
        Integer timeoutSec,
        List<String> tags,
        List<NodeDoc> nodes,
        List<EdgeDoc> edges
) {
    public static final int CURRENT_FORMAT_VERSION = 1;

    /** Schedule sub-structure. */
    public record Schedule(
            String type,
            String cron,
            String start,    // ISO-8601 datetime
            String end,      // ISO-8601 datetime
            Long intervalMs
    ) {}

    /** Node sub-structure (data-model §5 node table). */
    public record NodeDoc(
            String key,
            String type,     // TASK | VIRTUAL
            String task,     // relative task path/slug (TASK nodes), omitted for VIRTUAL
            String name,
            List<Integer> pos  // [x, y] or null
    ) {}

    /** Edge sub-structure (data-model §5 edge table). */
    public record EdgeDoc(
            String from,     // source node key
            String to,       // target node key
            String strength  // WEAK when explicit; omitted (null) = STRONG
    ) {}
}
