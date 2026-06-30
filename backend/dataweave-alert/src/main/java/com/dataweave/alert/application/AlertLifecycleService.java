package com.dataweave.alert.application;

import com.dataweave.alert.domain.AlertEvent;
import com.dataweave.alert.domain.AlertRule;
import com.dataweave.alert.domain.AlertSilence;
import com.dataweave.alert.domain.AlertState;
import com.dataweave.alert.domain.repository.AlertEventRepository;
import com.dataweave.alert.domain.repository.AlertSilenceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 告警生命周期服务：状态机推进 + fingerprint 去重 + 抑制窗口 + auto_resolve + ACK。
 *
 * <p>不变量：
 * <ul>
 *   <li>状态转换幂等（CAS {@code WHERE state=?}）</li>
 *   <li>SUPPRESSED 优先级最高（命中 silence 不投递）</li>
 *   <li>RESOLVED/ACKED 后同 fingerprint 新触发可再建 FIRING</li>
 * </ul>
 */
@Service
public class AlertLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(AlertLifecycleService.class);
    private final AlertEventRepository eventRepo;
    private final AlertSilenceRepository silenceRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AlertLifecycleService(AlertEventRepository eventRepo, AlertSilenceRepository silenceRepo) {
        this.eventRepo = eventRepo;
        this.silenceRepo = silenceRepo;
    }

    /**
     * 处理新触发信号：查重去抖 → 新建或更新 → 返回需要分发的 event（或空）。
     *
     * @return 需要分发的 event，或 empty（被抑制/去重/静默）
     */
    public Optional<AlertEvent> onSignal(AlertRule rule, AlertEvent candidate) {
        // 1. 检查命中生效 silence → SUPPRESSED
        List<AlertSilence> silences = silenceRepo.findActiveByTenantId(rule.getTenantId());
        for (AlertSilence s : silences) {
            if (matchesSilence(s, rule)) {
                candidate.setState(AlertState.SUPPRESSED.name());
                eventRepo.save(candidate);
                log.info("[AlertLifecycle] silenced: rule={} fingerprint={}", rule.getId(), candidate.getFingerprint());
                return Optional.empty();
            }
        }

        // 2. 查是否已存在活跃的同 fingerprint
        Optional<AlertEvent> existing = eventRepo.findByTenantIdAndFingerprintAndState(
                candidate.getTenantId(), candidate.getFingerprint(), AlertState.FIRING.name());
        if (existing.isPresent()) {
            AlertEvent e = existing.get();
            int suppressWindowSec = rule.getSuppressWindowSec() != null ? rule.getSuppressWindowSec() : 300;
            LocalDateTime windowStart = e.getLastFiredAt();
            if (windowStart != null &&
                    windowStart.plusSeconds(suppressWindowSec).isAfter(LocalDateTime.now())) {
                // 抑制窗口内：只累加计数，不分发
                int newCount = (e.getCount() != null ? e.getCount() : 1) + 1;
                eventRepo.incrementCount(e.getId(), newCount);
                log.info("[AlertLifecycle] dedup: rule={} fingerprint={} count={}", rule.getId(), candidate.getFingerprint(), newCount);
                return Optional.empty();
            }
        }

        // 3. 新建 FIRING 事件
        candidate.setState(AlertState.FIRING.name());
        candidate.setCount(1);
        candidate.setFirstFiredAt(LocalDateTime.now());
        candidate.setLastFiredAt(LocalDateTime.now());
        eventRepo.save(candidate);
        log.info("[AlertLifecycle] fired: rule={} fingerprint={} severity={}", rule.getId(), candidate.getFingerprint(), candidate.getSeverity());
        return Optional.of(candidate);
    }

    /**
     * 自动恢复检查：条件清除时 FIRING → RESOLVED。
     */
    public Optional<AlertEvent> autoResolve(AlertRule rule, AlertEvent event) {
        if (rule.getAutoResolve() == null || rule.getAutoResolve() == 0) return Optional.empty();
        if (!AlertState.FIRING.name().equals(event.getState())) return Optional.empty();
        eventRepo.markResolved(event.getId());
        event.setState(AlertState.RESOLVED.name());
        event.setResolvedAt(LocalDateTime.now());
        log.info("[AlertLifecycle] resolved: rule={} fingerprint={}", rule.getId(), event.getFingerprint());
        return Optional.of(event);
    }

    /**
     * 人工 ACK：FIRING → ACKED。
     */
    public boolean ack(Long eventId, Long ackedBy) {
        Optional<AlertEvent> opt = eventRepo.findById(eventId);
        if (opt.isEmpty()) return false;
        AlertEvent e = opt.get();
        if (!AlertState.FIRING.name().equals(e.getState())) {
            log.warn("[AlertLifecycle] cannot ACK event {} in state {}", eventId, e.getState());
            return false;
        }
        eventRepo.markAcked(eventId, ackedBy);
        log.info("[AlertLifecycle] acked: event={} by={}", eventId, ackedBy);
        return true;
    }

    private boolean matchesSilence(AlertSilence silence, AlertRule rule) {
        try {
            String matchJson = silence.getMatchJson();
            if (matchJson == null || matchJson.isBlank()) return false;
            @SuppressWarnings("unchecked")
            Map<String, Object> match = objectMapper.readValue(matchJson, Map.class);
            // 简单 label 匹配：检查规则的 labels_json 中包含 match 中的 key=value
            String labelsJson = rule.getLabelsJson();
            if (labelsJson == null || labelsJson.isBlank()) return false;
            @SuppressWarnings("unchecked")
            Map<String, Object> labels = objectMapper.readValue(labelsJson, Map.class);
            for (var entry : match.entrySet()) {
                Object labelVal = labels.get(entry.getKey());
                if (labelVal == null || !String.valueOf(labelVal).equals(String.valueOf(entry.getValue()))) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            log.warn("Failed to check silence match: {}", e.getMessage());
            return false;
        }
    }
}
