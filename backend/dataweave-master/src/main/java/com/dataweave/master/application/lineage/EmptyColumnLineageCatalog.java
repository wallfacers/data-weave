package com.dataweave.master.application.lineage;

import java.util.Optional;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@link ColumnLineageCatalog} 的默认空实现（列级血缘 catalog 方案 D，v1）。
 *
 * <p>把 {@code ColumnLineageCatalog.EMPTY} 从静态字面量提为可注入 Bean：解析调用方
 * （{@code TaskService}/{@code ProjectSyncService}）注入本接口，未来替换为真实 catalog
 * （neo4j {@code :Column} 读取侧 / 任务列声明 / 运行时 schema 反射）时零改调用点。
 * v1 语义同 {@link ColumnLineageCatalog#EMPTY}——任何表都查不到，迫使列级解析走降级
 * （UNVERIFIED/退表级），契合 019「解析是增强、绝不抛」契约。
 *
 * <p>真实列元数据来源在全仓尚不存在（鸡生蛋：019 产 {@code ColumnEdge} 靠 catalog，
 * 018 写 {@code :Column} 又靠 019 的 {@code ColumnEdge}），列为后续 feature
 * （见 specs/019 research D2 / contracts §5）。
 *
 * <p>024：改为 {@code @ConditionalOnProperty(lineage.column-catalog.type=empty)}（matchIfMissing=true），
 * H2/默认 fallback 空实现。neo4j 环境装配 {@link com.dataweave.master.infrastructure.lineage.Neo4jColumnLineageCatalog}。
 */
@Component
@ConditionalOnProperty(name = "lineage.column-catalog.type", havingValue = "empty", matchIfMissing = true)
public class EmptyColumnLineageCatalog implements ColumnLineageCatalog {

    @Override
    public Optional<TableSchema> lookupTable(long tenantId, long projectId, String qualifiedName) {
        return Optional.empty();
    }
}
