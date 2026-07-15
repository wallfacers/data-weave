package com.dataweave.master.companion.domain;

import java.time.LocalDateTime;

/**
 * 巡检概况（snapshot/briefing 事件携带）。服务端现算，非缓存（同 incident 战况播报一致性契约）。
 *
 * @param todayRuns      今日巡检轮次数
 * @param openAnomalies  未关闭异常数（DANGER+WARN）
 * @param nextPatrolAt   启用例程中最近的下次触发时间；无启用例程则 null
 */
public record Briefing(int todayRuns, int openAnomalies, LocalDateTime nextPatrolAt) {
    public static Briefing empty() {
        return new Briefing(0, 0, null);
    }
}
