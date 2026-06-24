package com.dataweave.alert.application;

import com.dataweave.alert.domain.NotificationSender;
import com.dataweave.alert.infrastructure.LogNotificationChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 运维告警监听器：接收来自 Stream C 巡检的告警载荷，路由到通知渠道。
 *
 * <p>M1 最小链路：全部走 {@link LogNotificationChannel} 打日志。M2 扩展为按规则
 * 匹配 channel（邮件/IM/Webhook 等）。
 *
 * <p>告警载荷结构（契约③）：{@code {id, kind, severity, title, detail, instanceIds[], suggestedAction?}}
 */
@Component
public class OpsAlertListener {

    private static final Logger log = LoggerFactory.getLogger(OpsAlertListener.class);

    private final List<NotificationSender> senders;

    public OpsAlertListener(List<NotificationSender> senders) {
        this.senders = senders;
    }

    /**
     * 接收并分发告警：遍历所有已注册的 {@link NotificationSender}，调用 send。
     *
     * @param alert 契约③告警载荷
     */
    @SuppressWarnings("unchecked")
    public void onAlert(Map<String, Object> alert) {
        String kind = (String) alert.getOrDefault("kind", "UNKNOWN");
        String severity = (String) alert.getOrDefault("severity", "info");
        String title = (String) alert.getOrDefault("title", "无标题");
        String detail = (String) alert.getOrDefault("detail", "");
        List<String> instanceIds = (List<String>) alert.get("instanceIds");

        String message = String.format("[%s][%s] %s — %s (instances: %s)",
                kind, severity, title, detail,
                instanceIds != null ? String.join(",", instanceIds) : "none");

        for (NotificationSender sender : senders) {
            try {
                sender.send(title, message);
                log.debug("[OpsAlertListener] Alert dispatched to channel={} kind={}",
                        sender.name(), kind);
            } catch (Exception e) {
                log.warn("[OpsAlertListener] Failed to send alert to channel={}: {}",
                        sender.name(), e.getMessage());
            }
        }
    }

    /**
     * 便捷方法：从 SLAService 预测结果直接触发 SLA_RISK 告警。
     */
    public void onSlaRisk(String instanceId, long etaMs, long thresholdMs, String taskInfo) {
        String title = "SLA 风险预测";
        String message = String.format(
                "[SLA_RISK][warning] 实例 %s 预计耗时 %dms 超出 SLA %dms — 任务: %s",
                instanceId, etaMs, thresholdMs, taskInfo);

        for (NotificationSender sender : senders) {
            try {
                sender.send(title, message);
            } catch (Exception e) {
                log.warn("[OpsAlertListener] SLA risk alert failed for channel={}: {}",
                        sender.name(), e.getMessage());
            }
        }
    }
}
