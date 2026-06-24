package com.dataweave.api.application;

import com.dataweave.api.infrastructure.OpsMessages;
import com.dataweave.master.domain.TaskInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运维巡检告警服务：实例进入 FAILED 状态时生成 AG-UI CUSTOM(dataweave.ops.alert) 事件载荷。
 *
 * <p>去重策略：按 instanceId+kind 内存去重（同一实例同类型告警不重复发），重启清空。
 *
 * <p>AG-UI 事件经 {@link AguiEvents#custom} 出口；payload 结构见契约③。
 */
@Service
public class OpsAlertService {

    private static final Logger log = LoggerFactory.getLogger(OpsAlertService.class);

    private final Set<String> emitted = ConcurrentHashMap.newKeySet();
    private final OpsMessages messages;

    public OpsAlertService(OpsMessages messages) {
        this.messages = messages;
    }

    /**
     * 为进入 FAILED 态的实例构建告警载荷。已发过的 instance+kind 组合返回 null。
     */
    public Map<String, Object> buildFailedAlert(TaskInstance instance, Locale locale) {
        String dedupKey = instance.getId() + ":INSTANCE_FAILED";
        if (!emitted.add(dedupKey)) {
            return null; // 已发过
        }

        String title = messages.get("ops.alert.failed.title", locale,
                instance.getTaskId(), instance.getBizDate());
        String detail = messages.get("ops.alert.failed.detail", locale,
                instance.getId(), instance.getState());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", UUID.randomUUID().toString());
        payload.put("kind", "INSTANCE_FAILED");
        payload.put("severity", "error");
        payload.put("title", title);
        payload.put("detail", detail);
        payload.put("instanceIds", List.of(instance.getId().toString()));

        // 建议动作：重跑
        Map<String, Object> suggested = new LinkedHashMap<>();
        suggested.put("op", "rerun");
        suggested.put("instanceId", instance.getId().toString());
        payload.put("suggestedAction", suggested);

        log.info("[OpsAlert] INSTANCE_FAILED: instance={} task={} bizDate={}",
                instance.getId(), instance.getTaskId(), instance.getBizDate());
        return payload;
    }

    /**
     * 为 SLA 风险构建告警载荷（运行中实例预测超时）。
     */
    public Map<String, Object> buildSlaRiskAlert(TaskInstance instance, long etaMs, long thresholdMs, Locale locale) {
        String dedupKey = instance.getId() + ":SLA_RISK";
        if (!emitted.add(dedupKey)) {
            return null;
        }

        String title = messages.get("ops.alert.sla_risk.title", locale,
                instance.getTaskId(), instance.getBizDate());
        String detail = messages.get("ops.alert.sla_risk.detail", locale,
                instance.getId(), etaMs / 1000, thresholdMs / 1000);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", UUID.randomUUID().toString());
        payload.put("kind", "SLA_RISK");
        payload.put("severity", "warning");
        payload.put("title", title);
        payload.put("detail", detail);
        payload.put("instanceIds", List.of(instance.getId().toString()));

        Map<String, Object> suggested = new LinkedHashMap<>();
        suggested.put("op", "kill");
        suggested.put("instanceId", instance.getId().toString());
        payload.put("suggestedAction", suggested);

        log.info("[OpsAlert] SLA_RISK: instance={} task={} etaMs={} thresholdMs={}",
                instance.getId(), instance.getTaskId(), etaMs, thresholdMs);
        return payload;
    }

    /**
     * 为补数据完成构建告警载荷。
     */
    public Map<String, Object> buildBackfillDoneAlert(UUID runId, String state, int total, int success, int failed, Locale locale) {
        String dedupKey = runId + ":BACKFILL_DONE";
        if (!emitted.add(dedupKey)) {
            return null;
        }

        String severity = "SUCCESS".equals(state) ? "info" : "warning";
        String title = messages.get("ops.alert.backfill_done.title", locale, runId, state);
        String detail = messages.get("ops.alert.backfill_done.detail", locale, total, success, failed);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", UUID.randomUUID().toString());
        payload.put("kind", "BACKFILL_DONE");
        payload.put("severity", severity);
        payload.put("title", title);
        payload.put("detail", detail);
        payload.put("instanceIds", List.of());

        log.info("[OpsAlert] BACKFILL_DONE: runId={} state={} success={}/{}", runId, state, success, total);
        return payload;
    }

    /** 清空去重缓存（测试/重启用）。 */
    public void clearCache() {
        emitted.clear();
    }
}
