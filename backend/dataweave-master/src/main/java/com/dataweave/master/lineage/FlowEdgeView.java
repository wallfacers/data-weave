package com.dataweave.master.lineage;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Objects;

/**
 * 统一血缘流边视图（返回 DTO）。
 *
 * <p>从 neo4j 关系投影，连接两个 {@link GraphNodeView}。表级流对应
 * {@code FLOWS_TO}，列级流对应 {@code DERIVES_FROM}。
 *
 * <p>041 扩展（@JsonInclude NON_NULL，旧客户端无感）：{@code source} 来源通道、
 * {@code humanState} 人工裁决态（CONFIRMED；REMOVED 边不出图）、{@code modelVersion} 模型版本。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FlowEdgeView(
        /** 源节点 id。 */
        String from,
        /** 目标节点 id。 */
        String to,
        /** 血缘粒度。 */
        GraphNodeView.Granularity granularity,
        /** 产生此边的任务 id（来自关系属性）。 */
        Long taskDefId,
        /** A×B 交叉校验置信度。 */
        Confidence confidence,
        /** 列级转换类型（仅 COLUMN 粒度有意义）。 */
        Transform transform,
        /** 来源通道（AGENT/SQL_PARSED/FORM/SCRIPT_SQL/SCRIPT_INFERRED/SCRIPT_MODEL；旧边可 null）。 */
        String source,
        /** 人工裁决态（CONFIRMED；无裁决 null）。 */
        String humanState,
        /** 模型版本（仅 SCRIPT_MODEL 边）。 */
        String modelVersion) {

    public FlowEdgeView {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(granularity, "granularity");
    }

    /** 兼容构造（041 前六参形态）。 */
    public FlowEdgeView(String from, String to, GraphNodeView.Granularity granularity,
                        Long taskDefId, Confidence confidence, Transform transform) {
        this(from, to, granularity, taskDefId, confidence, transform, null, null, null);
    }

    /** 简洁构造（无 taskDefId/confidence/transform）。 */
    public FlowEdgeView(String from, String to, GraphNodeView.Granularity granularity) {
        this(from, to, granularity, null, null, null, null, null, null);
    }

    /** 表级边构造（带 taskDefId + confidence）。 */
    public FlowEdgeView(String from, String to, Long taskDefId, Confidence confidence) {
        this(from, to, GraphNodeView.Granularity.TABLE, taskDefId, confidence, null, null, null, null);
    }

    /** 置信度枚举。 */
    public enum Confidence {
        CONFIRMED,
        UNVERIFIED,
        CONFLICT,
        DECLARED
    }

    /** 列级转换类型枚举。 */
    public enum Transform {
        DIRECT,
        EXPRESSION,
        AGGREGATE
    }
}
