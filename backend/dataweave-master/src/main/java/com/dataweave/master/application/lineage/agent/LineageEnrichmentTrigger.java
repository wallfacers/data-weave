package com.dataweave.master.application.lineage.agent;

import com.dataweave.master.domain.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

/**
 * 053 富化事件发布封装：push 同步路径记完确定性血缘后调 {@link #request}，
 * 把 {@link LineageAgentEnrichmentRequested} 序列化为 JSON 发到 {@link LineageAgentEnrichmentRequested#CHANNEL}，
 * 由 {@code LineageAgentEnricher} 异步消费。发布失败绝不阻断 push（FR-004b）。
 *
 * <p>封装目的：让 {@code TaskService}/{@code ProjectSyncService} 只注入本触发器一个 bean，
 * 不直接依赖 {@link EventBus} + {@link ObjectMapper}。
 */
@Component
public class LineageEnrichmentTrigger {
    private static final Logger log = LoggerFactory.getLogger(LineageEnrichmentTrigger.class);

    private final EventBus eventBus;
    private final ObjectMapper objectMapper;

    public LineageEnrichmentTrigger(EventBus eventBus, ObjectMapper objectMapper) {
        this.eventBus = eventBus;
        this.objectMapper = objectMapper;
    }

    /** 发布一次富化请求；任何异常吞掉留痕，绝不外抛（FR-004b）。agentReads/Writes 用于重算时保留声明边不被 keyed replace 擦除。 */
    public void request(long tenantId, long projectId, long taskDefId, String taskType, boolean calciteParsed,
                        java.util.List<String> agentReads, java.util.List<String> agentWrites) {
        try {
            String json = objectMapper.writeValueAsString(
                    new LineageAgentEnrichmentRequested(tenantId, projectId, taskDefId, taskType, calciteParsed,
                            agentReads, agentWrites));
            eventBus.publish(LineageAgentEnrichmentRequested.CHANNEL, json);
        } catch (Exception e) {
            log.warn("[LineageAgent] enrichment request publish failed (bypassed, FR-004b): {}", e.toString());
        }
    }
}
