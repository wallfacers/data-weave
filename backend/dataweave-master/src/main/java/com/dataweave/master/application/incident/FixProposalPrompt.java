package com.dataweave.master.application.incident;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.dataweave.master.domain.TaskDef;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 067 修复提案提示组装 + 结构化输出解析（US2/FR-007 CODE 分型）：产出全量新脚本内容 + 变更说明，
 * 与 push 幂等覆盖语义一致（非 diff）。解析失败一律返回空，绝不产出半截/空白脚本覆盖任务。
 */
@Component
public class FixProposalPrompt {

    private static final int CONTENT_BUDGET_CHARS = 6000;
    private static final Pattern JSON_BLOCK = Pattern.compile("\\{[\\s\\S]*\\}");

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 修复提案：全量新脚本内容 + 变更说明。 */
    public record FixProposal(String content, String changeSummary) {
    }

    public String systemPrompt(String agentLocale) {
        String lang = (agentLocale == null || agentLocale.isBlank()) ? "zh-CN" : agentLocale;
        return "You are Weft's ops-agent generating a full corrected replacement for a failing data task script. " +
                "Given the original task definition content, failure log tail and diagnosis suggestion, " +
                "output ONLY a single JSON object, no markdown fences, no extra text, matching exactly: " +
                "{\"content\":\"...\",\"changeSummary\":\"...\"}. " +
                "\"content\" MUST be the COMPLETE corrected script (same format/language/type as the original — " +
                "do not use diff markers, do not omit unchanged parts, do not truncate). " +
                "\"changeSummary\" is a concise (<=200 chars) human-readable description of what changed and why, " +
                "in this language: " + lang + ".";
    }

    public String userPrompt(TaskDef taskDef, IncidentEvidenceCollector.Evidence evidence, String diagnosisSuggestion) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Task\n");
        sb.append("name: ").append(taskDef.getName()).append('\n');
        sb.append("type: ").append(taskDef.getType()).append('\n');
        sb.append("\n## Original task definition content\n");
        String content = taskDef.getContent();
        if (content == null || content.isEmpty()) {
            sb.append("(empty)\n");
        } else {
            sb.append(content.length() > CONTENT_BUDGET_CHARS
                    ? content.substring(0, CONTENT_BUDGET_CHARS) + "...(truncated)" : content);
            sb.append('\n');
        }
        sb.append("\n## Diagnosis suggestion\n");
        sb.append(diagnosisSuggestion == null || diagnosisSuggestion.isBlank() ? "(none)" : diagnosisSuggestion);
        sb.append("\n\n## Failure log tail\n");
        sb.append(evidence.logTail() == null || evidence.logTail().isEmpty() ? "(empty)" : evidence.logTail());
        return sb.toString();
    }

    /** 解析失败或 content 为空一律返回空——调用方须转人工，绝不可拿半截结果覆盖任务。 */
    @SuppressWarnings("unchecked")
    public Optional<FixProposal> parse(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return Optional.empty();
        }
        try {
            Matcher m = JSON_BLOCK.matcher(rawText);
            if (!m.find()) {
                return Optional.empty();
            }
            Map<String, Object> root = objectMapper.readValue(m.group(), new TypeReference<Map<String, Object>>() {});
            Object contentObj = root.get("content");
            if (!(contentObj instanceof String content) || content.isBlank()) {
                return Optional.empty();
            }
            Object summaryObj = root.get("changeSummary");
            String changeSummary = summaryObj != null ? summaryObj.toString() : "";
            return Optional.of(new FixProposal(content, changeSummary));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
