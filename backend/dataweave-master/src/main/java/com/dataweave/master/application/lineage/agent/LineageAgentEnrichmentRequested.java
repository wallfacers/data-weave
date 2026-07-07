package com.dataweave.master.application.lineage.agent;

/**
 * 053 请求异步 AI 富化的事件（FR-004b）。push 同步路径记完确定性血缘后由 {@link LineageEnrichmentTrigger} 发布，
 * {@code LineageAgentEnricher} 在有界线程池内消费、补 {@code SCRIPT_AGENT} 边并入图谱。
 *
 * @param calciteParsed SQL 任务是否 Calcite 正常解析（D7：仅解析失败才触发 AI 兜底；脚本任务恒 false）。
 *                      为 true 时 enricher 跳过 AI 外呼（Calcite 已是确定性最优，不做冗余调用）。
 */
public record LineageAgentEnrichmentRequested(
        long tenantId,
        long projectId,
        long taskDefId,
        String taskType,
        boolean calciteParsed,
        java.util.List<String> agentReads,
        java.util.List<String> agentWrites
) {
    /** EventBus 频道名（EventBus 为 string channel + JSON message，见 EventBus 契约）。 */
    public static final String CHANNEL = "dw:lineage:agent:enrich";
}
