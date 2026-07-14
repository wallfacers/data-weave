package com.dataweave.master.application.incident;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.dataweave.master.domain.TaskDef;
import com.dataweave.master.domain.TaskInstance;
import com.dataweave.master.domain.incident.IncidentClassifications;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 067 诊断提示组装 + 结构化输出解析（US1/FR-003）。系统提示要求模型只回复单个 JSON 对象
 * （无强制 tool_call，普通 chat 通道即可）；解析失败一律降级 UNKNOWN，绝不抛异常拖垮编排。
 */
@Component
public class DiagnosisPrompt {

    private static final int CONTENT_BUDGET_CHARS = 4000;
    private static final Set<String> ALLOWED_CLASSIFICATIONS = Set.of(
            IncidentClassifications.TRANSIENT, IncidentClassifications.RESOURCE, IncidentClassifications.CODE,
            IncidentClassifications.UPSTREAM_DATA, IncidentClassifications.CONFIG_CREDENTIAL,
            IncidentClassifications.UNKNOWN);
    private static final Set<String> ALLOWED_CONFIDENCE = Set.of("HIGH", "MEDIUM", "LOW");
    private static final Pattern JSON_BLOCK = Pattern.compile("\\{[\\s\\S]*\\}");

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 诊断结论：分型 + 置信度 + 证据行引用 + 处置建议。 */
    public record Diagnosis(String classification, String confidence, List<String> evidenceLines, String suggestion) {
        public static Diagnosis unknown(String reason) {
            return new Diagnosis(IncidentClassifications.UNKNOWN, "LOW", List.of(), reason);
        }
    }

    public String systemPrompt(String agentLocale) {
        String lang = (agentLocale == null || agentLocale.isBlank()) ? "zh-CN" : agentLocale;
        return "You are Weft's ops-agent, an automated incident diagnosis assistant for a data task " +
                "scheduling platform. Given failure evidence (log tail, task definition, run history), " +
                "classify the failure and suggest a fix. " +
                "Respond with ONLY a single JSON object, no markdown fences, no extra text, matching exactly: " +
                "{\"classification\":\"TRANSIENT|RESOURCE|CODE|UPSTREAM_DATA|CONFIG_CREDENTIAL|UNKNOWN\"," +
                "\"confidence\":\"HIGH|MEDIUM|LOW\",\"evidenceLines\":[\"...\"],\"suggestion\":\"...\"}. " +
                "TRANSIENT = node blip / one-off timeout, likely to succeed on retry. " +
                "RESOURCE = out-of-memory / resource exhaustion, likely fixable by raising CPU/memory. " +
                "CODE = a defect in the task script itself (syntax error, bad logic, wrong reference). " +
                "UPSTREAM_DATA = the failure is caused by dirty/unexpected data from an upstream source, not the task code. " +
                "CONFIG_CREDENTIAL = authentication/credential/connection configuration problem — DO NOT use this " +
                "unless the log clearly shows an auth/credential/connection failure. " +
                "UNKNOWN = none of the above clearly applies. " +
                "Write the \"suggestion\" field in this language: " + lang + ".";
    }

    public String userPrompt(IncidentEvidenceCollector.Evidence ev) {
        TaskInstance ti = ev.failedInstance();
        TaskDef td = ev.taskDef();
        StringBuilder sb = new StringBuilder();
        sb.append("## Task\n");
        sb.append("name: ").append(ti.getTaskDefName()).append('\n');
        sb.append("type: ").append(td != null ? td.getType() : ti.getTaskType()).append('\n');
        sb.append("streaming(long_running): ").append(ev.streaming()).append('\n');
        sb.append("failure_reason: ").append(ti.getFailureReason()).append('\n');
        sb.append("exit_code: ").append(ti.getExitCode()).append('\n');
        sb.append("error_message: ").append(ti.getErrorMessage()).append('\n');
        if (td != null && td.getContent() != null) {
            sb.append("\n## Task definition content (truncated)\n");
            String content = td.getContent();
            sb.append(content.length() > CONTENT_BUDGET_CHARS ? content.substring(0, CONTENT_BUDGET_CHARS) + "...(truncated)" : content);
            sb.append('\n');
        }
        sb.append("\n## Log tail\n");
        sb.append(ev.logTail() == null || ev.logTail().isEmpty() ? "(empty)" : ev.logTail());
        sb.append("\n\n## Recent run history (most recent first)\n");
        if (ev.history().isEmpty()) {
            sb.append("(no prior runs)\n");
        } else {
            for (IncidentEvidenceCollector.HistoryEntry h : ev.history()) {
                sb.append("- state=").append(h.state())
                        .append(" exitCode=").append(h.exitCode())
                        .append(" startedAt=").append(h.startedAt())
                        .append(" finishedAt=").append(h.finishedAt())
                        .append('\n');
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    public Diagnosis parse(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return Diagnosis.unknown("empty LLM response");
        }
        try {
            Matcher m = JSON_BLOCK.matcher(rawText);
            if (!m.find()) {
                return Diagnosis.unknown("no JSON object found in LLM response");
            }
            Map<String, Object> root = objectMapper.readValue(m.group(), new TypeReference<Map<String, Object>>() {});
            String classification = normalize((String) root.get("classification"),
                    ALLOWED_CLASSIFICATIONS, IncidentClassifications.UNKNOWN);
            String confidence = normalize((String) root.get("confidence"), ALLOWED_CONFIDENCE, "LOW");
            List<String> evidenceLines = new ArrayList<>();
            if (root.get("evidenceLines") instanceof List<?> list) {
                for (Object o : list) {
                    if (o != null) evidenceLines.add(o.toString());
                }
            }
            String suggestion = root.get("suggestion") != null ? root.get("suggestion").toString() : null;
            return new Diagnosis(classification, confidence, evidenceLines, suggestion);
        } catch (Exception e) {
            return Diagnosis.unknown("parse failed: " + e.getMessage());
        }
    }

    private String normalize(String value, Set<String> allowed, String fallback) {
        if (value == null) return fallback;
        String upper = value.toUpperCase(Locale.ROOT).trim();
        return allowed.contains(upper) ? upper : fallback;
    }
}
