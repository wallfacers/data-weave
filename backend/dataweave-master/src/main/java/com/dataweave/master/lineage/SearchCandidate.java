package com.dataweave.master.lineage;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 按名搜索数据资产的候选结果（US2 / FR-008/009/011/022）。
 *
 * <p>MVP toLower CONTAINS 中缀匹配（Table→qualifiedName，Column→name，Metric→name），
 * 结果严格经 tenantId/projectId 过滤。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SearchCandidate(
        /** 节点 id（供后续以其为锚点查询）。 */
        String id,
        /** 节点类型（TABLE|COLUMN|METRIC）。 */
        String type,
        /** 展示名：Table=qualifiedName / Column=name / Metric=name。 */
        String name,
        /** 层标签（ODS/DWD/…；Table 有，Column/Metric 为 null）。 */
        String layer,
        /** 消歧信息：Table=datasourceId / Column=tableKey / Metric=metricType。 */
        String datasource) {
}
