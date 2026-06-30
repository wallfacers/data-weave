package com.dataweave.master.domain.lineage;

/**
 * 表引用：归属某 Datasource（去重后图 id 由 store 解析）+ 限定名 + 分层。
 *
 * <p>{@code qualifiedName} 大小写规范化与现 {@code SqlTableExtractor} 一致（保留原始拼写，比对在装配层大小写不敏感）；
 * 图内 tableKey 由 store 按 {@code dsKey + qualifiedName(lower)} 合成以保证去重稳定。
 */
public record TableRef(
        DatasourceCoord datasource,
        String qualifiedName,   // 大小写规范化与现 SqlTableExtractor 一致
        String layer            // ODS/DWD/DWS/ADS，可 null（由命名前缀推导）
) {}
