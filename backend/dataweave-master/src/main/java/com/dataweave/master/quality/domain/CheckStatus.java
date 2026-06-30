package com.dataweave.master.quality.domain;

import java.util.Collection;

/**
 * 单断言结果状态 + run 整体归约。
 *
 * <p><b>FR-007 红线 —— 基础设施失败 vs 断言失败语义分离</b>：
 * <ul>
 *   <li>{@link #ERROR} —— probe 返回 SKIPPED（未绑库 / 连不上 / 无驱动）→ 基础设施失败；
 *       <b>不发 {@code QUALITY_FAILED}、不阻断下游、不计入质量分</b>（SC-005）。</li>
 *   <li>{@link #FAIL} —— 真读回度量值且违反期望 → 数据问题 → 按 {@link RuleAction} 阻断/告警 + 发信号。</li>
 *   <li>{@link #WARN} —— 违反期望但 severity 仅警示（action 可降级）。</li>
 *   <li>{@link #PASS} —— 满足期望。</li>
 * </ul>
 */
public enum CheckStatus {
    PASS, FAIL, WARN, ERROR;

    /**
     * run 整体状态归约（data-model）：有 {@code FAIL} → {@code FAIL}；无 FAIL 有 {@code ERROR} → {@code ERROR}；
     * 无 FAIL/ERROR 有 {@code WARN} → {@code WARN}；全 {@code PASS} → {@code PASS}。
     *
     * <p>FAIL 优先于 ERROR：run 既有数据问题(FAIL)又有基础设施失败(ERROR) 时，整体标 FAIL
     * （数据问题需触发阻断/信号；ERROR 的规则各自不发信号/不计分，互不污染）。
     */
    public static CheckStatus aggregate(Collection<CheckStatus> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return PASS;
        }
        boolean hasFail = false, hasError = false, hasWarn = false;
        for (CheckStatus s : statuses) {
            switch (s) {
                case FAIL -> hasFail = true;
                case ERROR -> hasError = true;
                case WARN -> hasWarn = true;
                default -> { /* PASS contributes nothing */ }
            }
        }
        if (hasFail) return FAIL;
        if (hasError) return ERROR;
        if (hasWarn) return WARN;
        return PASS;
    }
}
