package com.dataweave.master.lineage;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;
import java.util.Objects;

/**
 * 统一血缘图节点视图（返回 DTO）。
 *
 * <p>从 neo4j 节点投影，经租户裁剪后透给前端。携带 {@link #type} / {@link #granularity}
 * 供前端分层渲染（FR-008）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GraphNodeView(
        /** 图内稳定 id（:Table/:Column 由应用层生成；:Task/:Metric 镜像 PG 主键）。 */
        String id,
        /** 节点类型。 */
        NodeType type,
        /** 展示名。 */
        String name,
        /** 分层标签（表的 ODS/DWD/DWS/ADS；datasource 为库名）。 */
        String layer,
        /** 粒度：表级血缘 = TABLE，列级 = COLUMN。非数据流节点（datasource/metrict）可为 null。 */
        Granularity granularity,
        /** 父节点 id（column→table、table→datasource），供前端三级树挂载。 */
        String parentId,
        /** 类型相关附加属性。 */
        Map<String, Object> attrs) {

    /** 简洁构造：无 granularity/parentId/attrs。 */
    public GraphNodeView(String id, NodeType type, String name, String layer) {
        this(id, type, name, layer, null, null, null);
    }

    /** 带 granularity 的构造。 */
    public GraphNodeView(String id, NodeType type, String name, String layer, Granularity granularity) {
        this(id, type, name, layer, granularity, null, null);
    }

    /** 紧凑构造（必填非空校验）。 */
    public GraphNodeView {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(name, "name");
    }

    /** 节点类型枚举。 */
    public enum NodeType {
        DATASOURCE,
        TABLE,
        COLUMN,
        METRIC
    }

    /** 血缘粒度枚举。 */
    public enum Granularity {
        TABLE,
        COLUMN
    }
}
