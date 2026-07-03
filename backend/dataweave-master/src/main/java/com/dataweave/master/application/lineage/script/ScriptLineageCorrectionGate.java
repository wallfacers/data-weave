package com.dataweave.master.application.lineage.script;

import java.util.Map;

/**
 * 人工修正裁决供给口（041 US3，FR-007）。编排器在产边后按语义键重放裁决：
 * REMOVED → 过滤不入图；CONFIRMED → 置信度升级。实现 = {@code LineageCorrectionService}；
 * 未注入实现（US3 落地前）时编排器视为无裁决。
 */
public interface ScriptLineageCorrectionGate {

    /** 语义键：{@code direction|tableKey}（表级）或 {@code direction|tableKey|columnLower}（字段级）。 */
    String STATUS_REMOVED = "REMOVED";
    String STATUS_CONFIRMED = "CONFIRMED";

    /** 该任务当前生效裁决：语义键 → REMOVED/CONFIRMED；无裁决返回空 map。 */
    Map<String, String> decisionsFor(long tenantId, long projectId, long taskDefId);
}
