package com.dataweave.master.filecontract.dto;

import java.util.List;
import java.util.Map;

/**
 * File shape for {@code <slug>.task.yaml} — task metadata (data-model §4).
 * The script body lives in a separate native file ({@code <slug>.<ext>}), not in this doc.
 */
public record TaskDoc(
        int formatVersion,
        String name,
        String type,
        String description,
        Integer priority,
        Integer timeoutSec,
        Integer retryMax,
        Boolean frozen,
        String datasource,
        String targetDatasource,
        Map<String, Object> params,
        List<String> tags
) {
    public static final int CURRENT_FORMAT_VERSION = 1;
}
