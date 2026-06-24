package com.dataweave.api.interfaces.dto;

import java.util.List;
import java.util.Map;

/**
 * 批量操作结果 — 契约①。
 */
public record BatchResult(
        int requested,
        int accepted,
        List<BatchResultItem> results
) {
    public record BatchResultItem(
            String id,
            String outcome,      // EXECUTED | PENDING_APPROVAL | REJECTED
            Long approvalId
    ) {}
}
