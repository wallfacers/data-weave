package com.dataweave.master.application.lineage;

import java.util.ArrayList;
import java.util.List;

import com.dataweave.master.domain.lineage.DatasourceCoord;

/**
 * 接缝适配器：把 019 解析层的列血缘（{@link ColumnLineageResult}，application 轻量类型）转为
 * 018 存储层的 {@link com.dataweave.master.domain.lineage.ColumnEdge}（domain 类型，带 {@link DatasourceCoord}）。
 *
 * <p>解析期只知列与表限定名，不知数据源坐标；坐标由调用方按方向提供（源列←读侧 coord，目标列←写侧 coord），
 * 与表级 {@link LineageEdgeAssembler} 的 {@code resolveCoord} 对齐，保证列挂到同一 {@code :Table} 节点（同 dsKey）。
 * {@code transform}/{@code confidence} 枚举名一一对应（DIRECT/EXPRESSION/AGGREGATE、CONFIRMED/UNVERIFIED/CONFLICT）。
 */
public final class ColumnLineageStoreAdapter {

    private ColumnLineageStoreAdapter() {
    }

    public static List<com.dataweave.master.domain.lineage.ColumnEdge> toDomain(
            ColumnLineageResult result, DatasourceCoord readCoord, DatasourceCoord writeCoord) {
        if (result == null || result.edges() == null || result.edges().isEmpty()) {
            return List.of();
        }
        List<com.dataweave.master.domain.lineage.ColumnEdge> out = new ArrayList<>();
        for (ColumnEdge e : result.edges()) {
            out.add(new com.dataweave.master.domain.lineage.ColumnEdge(
                    new com.dataweave.master.domain.lineage.TableRef(
                            readCoord, e.srcTable().qualifiedName(), null),
                    e.srcCol(),
                    new com.dataweave.master.domain.lineage.TableRef(
                            writeCoord, e.dstTable().qualifiedName(), null),
                    e.dstCol(),
                    com.dataweave.master.domain.lineage.Transform.valueOf(e.transform().name()),
                    com.dataweave.master.domain.lineage.Confidence.valueOf(e.confidence().name())));
        }
        return out;
    }
}
