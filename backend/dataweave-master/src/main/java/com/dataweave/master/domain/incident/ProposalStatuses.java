package com.dataweave.master.domain.incident;

/**
 * 修复提案状态常量（incident_proposal.status 取值）。
 * PENDING →(批准) APPROVED →(发布) PUBLISHED →(验证) VERIFIED | VERIFY_FAILED →(回滚) ROLLED_BACK
 * PENDING →(驳回) REJECTED；PENDING →(基线陈旧) STALE
 */
public final class ProposalStatuses {

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String STALE = "STALE";
    public static final String PUBLISHED = "PUBLISHED";
    public static final String VERIFIED = "VERIFIED";
    public static final String VERIFY_FAILED = "VERIFY_FAILED";
    public static final String ROLLED_BACK = "ROLLED_BACK";

    private ProposalStatuses() {
    }
}
