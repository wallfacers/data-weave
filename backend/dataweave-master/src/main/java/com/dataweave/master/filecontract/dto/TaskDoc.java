package com.dataweave.master.filecontract.dto;

import java.util.List;
import java.util.Map;

/**
 * File shape for {@code <slug>.task.yaml} — task metadata (data-model §4).
 * The script body lives in a separate native file ({@code <slug>.<ext>}), not in this doc.
 *
 * @param sparkMode   SPARK sub-mode: {@code pyspark | spark-sql | jar}；null for non-SPARK tasks
 * @param jarRef      jar asset reference (jar mode); null otherwise
 * @param mainClass   {@code --class} main class (jar mode); null otherwise
 * @param longRunning 062: external long-running (streaming) job marker (Flink streaming=true); null≡false
 * @param resources   069: declarative resource hints (e.g. {@code memoryMb}/{@code cpuCores}); null=engine default
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
        List<String> tags,
        String sparkMode,
        String jarRef,
        String mainClass,
        Boolean longRunning,
        Map<String, java.util.List<ColumnSchemaDecl>> declaredSchema,
        java.util.List<Map<String, String>> declaredColumnLineage,
        Map<String, Object> resources
) {
    public static final int CURRENT_FORMAT_VERSION = 1;
}
