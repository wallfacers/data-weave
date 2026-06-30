package com.dataweave.master.quality.domain;

/**
 * 断言严重度。喂 021 {@code AlertSignal.severityHint}（规则可覆盖）；
 * 评分卡加权：{@link #CRITICAL}=3 / {@link #WARNING}=2 / {@link #INFO}=1（data-model 评分算法）。
 */
public enum Severity {
    CRITICAL(3),
    WARNING(2),
    INFO(1);

    private final int weight;

    Severity(int weight) {
        this.weight = weight;
    }

    /** 评分卡加权权重（越大越严重）。 */
    public int weight() {
        return weight;
    }
}
