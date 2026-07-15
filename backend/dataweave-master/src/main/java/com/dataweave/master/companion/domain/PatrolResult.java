package com.dataweave.master.companion.domain;

/**
 * headless 单轮巡检的结构化产出（brain 返回 JSON 的解析结果）。
 *
 * <p>成功（{@link #ok}）：{@code severity/title/summary/detailJson} 来自 brain 产出的结构化 JSON
 * （schema 见 companion.yaml system_prompt：severity∈{DANGER,WARN,OK,INFO}，detail 含 objects/aggregateCount/suggestions）。
 * <p>失败（{@link #failed}）：brain 不可用/超时/JSON 解析失败——由 {@code PatrolService} 兜底产 INFO"未完成"汇报（SC-007）。
 */
public record PatrolResult(boolean ok, String severity, String title, String summary, String detailJson, String error) {

    public static PatrolResult ok(String severity, String title, String summary, String detailJson) {
        return new PatrolResult(true, severity, title, summary, detailJson, null);
    }

    public static PatrolResult failed(String error) {
        return new PatrolResult(false, null, null, null, null, error);
    }
}
