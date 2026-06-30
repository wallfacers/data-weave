package com.dataweave.master.domain.lineage;

/** 列级血缘变换类型（列→列流边 DERIVES_FROM.transform）。 */
public enum Transform {
    DIRECT,
    EXPRESSION,
    AGGREGATE
}
