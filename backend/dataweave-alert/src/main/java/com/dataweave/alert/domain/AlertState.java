package com.dataweave.alert.domain;

/**
 * 告警事件生命周期状态机。
 *
 * <p>转换幂等（CAS {@code WHERE state=?}）：
 * <ul>
 *   <li>信号满足(新 fingerprint) → FIRING</li>
 *   <li>抑制窗口内同 fingerprint 再满足 → count++ / last_fired_at (自环, 不分发)</li>
 *   <li>命中 silence → SUPPRESSED (优先级最高, 不投递)</li>
 *   <li>人工 ACK → ACKED (窗口内不再分发)</li>
 *   <li>auto_resolve 且条件清除 → RESOLVED (发恢复通知)</li>
 * </ul>
 */
public enum AlertState {
    FIRING,
    RESOLVED,
    ACKED,
    SUPPRESSED
}
