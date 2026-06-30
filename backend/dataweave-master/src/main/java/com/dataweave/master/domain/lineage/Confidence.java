package com.dataweave.master.domain.lineage;

/** 血缘边可信度（沿用现 A×B 交叉校验语义）。 */
public enum Confidence {
    CONFIRMED,
    UNVERIFIED,
    CONFLICT
}
