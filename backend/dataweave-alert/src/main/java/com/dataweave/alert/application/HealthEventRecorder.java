package com.dataweave.alert.application;

import com.dataweave.alert.domain.HealthEvent;
import com.dataweave.alert.domain.repository.HealthEventRepository;
import com.dataweave.master.domain.signal.AlertSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 事件中心持久化记录器（027）：第二个 {@code @EventListener}，与 {@link AlertSignalListener}（告警分发）
 * **并行独立**。把每条 {@link AlertSignal} 旁路持久化为 {@link HealthEvent}，再匹配订阅分发。
 *
 * <p>FR-007：本监听器任何异常都不得影响告警分发链路（Spring 多 listener 独立调用 + 本方法整体 try-catch）。
 */
@Component
public class HealthEventRecorder {

    private static final Logger log = LoggerFactory.getLogger(HealthEventRecorder.class);

    private final HealthEventRepository eventRepo;
    private final EventCenterService eventCenterService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public HealthEventRecorder(HealthEventRepository eventRepo, EventCenterService eventCenterService) {
        this.eventRepo = eventRepo;
        this.eventCenterService = eventCenterService;
    }

    @EventListener
    public void onSignal(AlertSignal signal) {
        try {
            HealthEvent event = toHealthEvent(signal);
            eventRepo.record(event);
            eventCenterService.matchAndDispatch(event);
        } catch (Exception e) {
            // 旁路持久化失败绝不影响告警分发（FR-007）
            log.warn("[HealthEventRecorder] record failed type={} tenant={}: {}",
                    signal.getType(), signal.getTenantId(), e.getMessage());
        }
    }

    HealthEvent toHealthEvent(AlertSignal signal) {
        Map<String, Object> ctx = signal.getContext();
        HealthEvent e = new HealthEvent();
        e.setTenantId(signal.getTenantId());
        e.setType(signal.getType().name());
        e.setSeverity(signal.getSeverityHint());
        e.setFingerprint(signal.getFingerprintHint() != null ? signal.getFingerprintHint()
                : signal.getType().name());
        deriveRef(e, ctx);
        e.setSummary(buildSummary(signal, ctx));
        e.setContextJson(serialize(ctx));
        e.setLastOccurredAt(LocalDateTime.now());
        return e;
    }

    /** 从 context 推导关联对象（深链用+可读名称），按优先级取第一个命中。 */
    private void deriveRef(HealthEvent e, Map<String, Object> ctx) {
        if (ctx == null) return;
        if (ctx.get("taskId") != null) {
            e.setRefKind("TASK"); e.setRefId(str(ctx.get("taskId"))); e.setRefName(str(ctx.get("taskName")));
        } else if (ctx.get("metricKey") != null) {
            e.setRefKind("METRIC"); e.setRefId(str(ctx.get("metricKey"))); e.setRefName(str(ctx.get("metricKey")));
        } else if (ctx.get("metric_key") != null) {
            e.setRefKind("METRIC"); e.setRefId(str(ctx.get("metric_key"))); e.setRefName(str(ctx.get("metric_key")));
        } else if (ctx.get("datasetRef") != null) {
            e.setRefKind("TABLE"); e.setRefId(str(ctx.get("datasetRef"))); e.setRefName(str(ctx.get("datasetRef")));
        } else if (ctx.get("workflowId") != null) {
            e.setRefKind("WORKFLOW"); e.setRefId(str(ctx.get("workflowId"))); e.setRefName(str(ctx.get("workflowName")));
        }
    }

    private String buildSummary(AlertSignal signal, Map<String, Object> ctx) {
        if (ctx != null && ctx.get("message") != null) {
            return signal.getType().name() + ": " + str(ctx.get("message"));
        }
        // 无 message 的类型交给前端按 type+contextJson 拼装本地化文案；这里仅兜底（邮件通道等非 UI 消费者用）。
        String name = ctx != null ? firstNonNull(str(ctx.get("taskName")), str(ctx.get("workflowName"))) : null;
        String hint = name != null ? name : signal.getFingerprintHint();
        return signal.getType().name() + " @ " + hint;
    }

    private static String firstNonNull(String a, String b) { return a != null ? a : b; }

    private String serialize(Map<String, Object> ctx) {
        if (ctx == null || ctx.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(ctx);
        } catch (Exception e) {
            return null;
        }
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }
}
