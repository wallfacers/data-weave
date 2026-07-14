package com.dataweave.master.domain.incident;

/**
 * 故障分型常量（incident.classification 取值）。TRANSIENT/RESOURCE 走自动处置；
 * CODE 走人审修复提案；UPSTREAM_DATA/CONFIG_CREDENTIAL 零徒劳重试直接升级人工；UNKNOWN 至多一次试探。
 */
public final class IncidentClassifications {

    public static final String TRANSIENT = "TRANSIENT";
    public static final String RESOURCE = "RESOURCE";
    public static final String CODE = "CODE";
    public static final String UPSTREAM_DATA = "UPSTREAM_DATA";
    public static final String CONFIG_CREDENTIAL = "CONFIG_CREDENTIAL";
    public static final String UNKNOWN = "UNKNOWN";

    private IncidentClassifications() {
    }

    /** 零徒劳重试、直达 NEEDS_HUMAN 的分型（FR-008/FR-009）。 */
    public static boolean isNonSelfHealable(String classification) {
        return UPSTREAM_DATA.equals(classification) || CONFIG_CREDENTIAL.equals(classification);
    }

    /**
     * 不可自愈分型的兜底操作建议（FR-008「100% 附根因定位与可执行操作建议」）：LLM 未产出可用
     * suggestion（空白/解析失败）时的最后一道保底，避免升级人工却给不出任何指引。
     */
    public static String defaultSuggestion(String classification) {
        return switch (classification) {
            case CONFIG_CREDENTIAL -> "请检查该任务绑定数据源的连接凭据（用户名/密码/密钥）是否正确、"
                    + "是否已过期或被轮换；修正后可在事故线程内触发复验。";
            case UPSTREAM_DATA -> "请核实该任务读取的上游数据源本次产出是否存在脏数据/缺失/格式异常；"
                    + "Agent 不会修改上游数据，需人工定位源头并修复后触发复验。";
            default -> null;
        };
    }
}
