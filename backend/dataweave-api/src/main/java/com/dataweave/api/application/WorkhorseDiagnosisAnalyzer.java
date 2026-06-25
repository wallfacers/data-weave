package com.dataweave.api.application;

import com.dataweave.api.application.bridge.WorkhorseBridge;
import com.dataweave.api.application.bridge.WorkhorseHealth;
import com.dataweave.master.application.DiagnosisAnalyzer;
import com.dataweave.master.application.MockDiagnosisAnalyzer;
import com.dataweave.master.domain.TaskDef;
import com.dataweave.master.domain.TaskInstance;
import com.dataweave.master.domain.WorkerNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 真实大脑诊断分析器（{@code @Primary} 覆盖规则版 {@link MockDiagnosisAnalyzer}）。
 *
 * <p>把失败实例的真实遥测（节点内存/CPU/load、近 7 天同类失败次数、并发争抢、OOM 日志）打包成
 * 结构化诊断 prompt，经 {@link WorkhorseBridge#runHeadless} 跑一个 headless workhorse 会话，
 * 要求模型输出 JSON {@code {title, rootCause, suggestions[]}}，映射为 {@link Analysis}。
 *
 * <p>workhorse 不可用（{@link WorkhorseHealth#isHealthy()} 为 false）、超时或返回不可解析时，
 * 回落 {@link MockDiagnosisAnalyzer}，保证诊断永不缺席、编排骨架不变。
 */
@Component
@Primary
public class WorkhorseDiagnosisAnalyzer implements DiagnosisAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(WorkhorseDiagnosisAnalyzer.class);

    private static final String INSTRUCTIONS =
            "你是 DataWeave 数据平台的失败诊断分析器。依据给定的任务失败上下文（节点资源、并发、历史失败、报错日志）"
                    + "分析根因并给出可执行修复建议。只输出一个 JSON 对象，不要任何额外解释或 markdown 代码块，格式严格为："
                    + "{\"title\":\"简短标题\",\"rootCause\":\"根因结论（含关键证据）\","
                    + "\"suggestions\":[{\"action\":\"RERUN_MORE_MEMORY|MIGRATE_NODE|CAP_NODE_WEIGHT|RERUN\",\"label\":\"建议文案\"}]}";

    private final WorkhorseBridge bridge;
    private final WorkhorseHealth health;
    private final MockDiagnosisAnalyzer fallback;
    private final ObjectMapper objectMapper;
    private final long timeoutMs;

    public WorkhorseDiagnosisAnalyzer(WorkhorseBridge bridge,
                                      WorkhorseHealth health,
                                      MockDiagnosisAnalyzer fallback,
                                      ObjectMapper objectMapper,
                                      @Value("${agent.workhorse.diagnosis-timeout-ms:60000}") long timeoutMs) {
        this.bridge = bridge;
        this.health = health;
        this.fallback = fallback;
        this.objectMapper = objectMapper;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public Analysis analyze(TaskInstance failed, WorkerNode node, TaskDef task, Telemetry telemetry, Locale locale) {
        Telemetry tel = telemetry != null ? telemetry : Telemetry.EMPTY;
        if (!health.isHealthy()) {
            return fallback.analyze(failed, node, task, tel, locale);
        }
        try {
            String prompt = buildPrompt(failed, node, task, tel, locale);
            String out = bridge.runHeadless(INSTRUCTIONS, prompt).block(Duration.ofMillis(timeoutMs));
            Analysis parsed = parse(out, node, task, failed, tel);
            if (parsed != null) {
                return parsed;
            }
            log.warn("workhorse 诊断返回不可解析，回落规则诊断；原文前 200 字: {}",
                    out == null ? "null" : out.substring(0, Math.min(200, out.length())));
        } catch (Exception e) {
            log.warn("workhorse 诊断失败（{}），回落规则诊断", e.toString());
        }
        return fallback.analyze(failed, node, task, tel, locale);
    }

    /** 把真实遥测渲染成诊断 prompt。 */
    private String buildPrompt(TaskInstance failed, WorkerNode node, TaskDef task, Telemetry tel, Locale locale) {
        String taskName = task != null ? task.getName()
                : (failed != null ? String.valueOf(failed.getTaskId()) : "?");
        String nodeCode = node != null ? node.getNodeCode()
                : (failed != null && failed.getWorkerNodeCode() != null ? failed.getWorkerNodeCode() : "unknown");
        double mem = node != null && node.getMem() != null ? node.getMem() : 0;
        double cpu = node != null && node.getCpu() != null ? node.getCpu() : 0;
        double load = node != null && node.getLoadAvg() != null ? node.getLoadAvg() : 0;
        String logText = failed != null && failed.getLog() != null ? failed.getLog() : "";
        StringBuilder sb = new StringBuilder();
        sb.append("语言: ").append(locale != null ? locale.toLanguageTag() : "zh-CN").append('\n');
        sb.append("任务: ").append(taskName).append('\n');
        sb.append("失败节点: ").append(nodeCode).append('\n');
        sb.append("节点内存使用率: ").append(fmt(mem)).append("%\n");
        sb.append("节点CPU: ").append(fmt(cpu)).append("%\n");
        sb.append("节点load: ").append(fmt(load)).append('\n');
        sb.append("该节点当前并发任务数: ").append(tel.concurrentTasks()).append('\n');
        sb.append("近7天该任务在该节点失败次数: ").append(tel.failureCount7d()).append('\n');
        sb.append("报错日志: ").append(logText.length() > 2000 ? logText.substring(0, 2000) : logText);
        return sb.toString();
    }

    /**
     * 解析 LLM 输出为 {@link Analysis}：抽取 JSON 对象，读 title/rootCause/suggestions；
     * contextJson 用真实遥测自行组装（不信模型）。任何缺失/异常返回 null（由调用方回落）。
     */
    private Analysis parse(String out, WorkerNode node, TaskDef task, TaskInstance failed, Telemetry tel) {
        if (out == null || out.isBlank()) {
            return null;
        }
        String json = extractJson(out);
        if (json == null) {
            return null;
        }
        try {
            Map<String, Object> m = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
            String title = str(m.get("title"));
            String rootCause = str(m.get("rootCause"));
            if (title == null || title.isBlank() || rootCause == null || rootCause.isBlank()) {
                return null;
            }
            String suggestionsJson = normalizeSuggestions(m.get("suggestions"));
            String contextJson = buildContextJson(node, failed, tel);
            return new Analysis(title, rootCause, contextJson, suggestionsJson);
        } catch (Exception e) {
            return null;
        }
    }

    /** 截取首个 '{' 到末个 '}' 之间的子串（容忍模型在 JSON 外裹解释/代码块）。 */
    private String extractJson(String out) {
        int start = out.indexOf('{');
        int end = out.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return out.substring(start, end + 1);
    }

    /** 把模型给的 suggestions 规整为 {@code [{"action":..,"label":..}]}；缺失则给原地重跑兜底项。 */
    @SuppressWarnings("unchecked")
    private String normalizeSuggestions(Object raw) {
        try {
            if (raw instanceof List<?> list && !list.isEmpty()) {
                List<Map<String, Object>> norm = new java.util.ArrayList<>();
                for (Object item : list) {
                    if (item instanceof Map<?, ?> mp) {
                        Object action = ((Map<String, Object>) mp).get("action");
                        Object label = ((Map<String, Object>) mp).get("label");
                        if (label != null) {
                            norm.add(Map.of(
                                    "action", action != null ? action : "RERUN",
                                    "label", label));
                        }
                    }
                }
                if (!norm.isEmpty()) {
                    return objectMapper.writeValueAsString(norm);
                }
            }
        } catch (Exception ignored) {
            // 落到兜底
        }
        return "[{\"action\":\"RERUN\",\"label\":\"原地重跑\"}]";
    }

    private String buildContextJson(WorkerNode node, TaskInstance failed, Telemetry tel) {
        String nodeCode = node != null ? node.getNodeCode()
                : (failed != null && failed.getWorkerNodeCode() != null ? failed.getWorkerNodeCode() : "unknown");
        double mem = node != null && node.getMem() != null ? node.getMem() : 0;
        double cpu = node != null && node.getCpu() != null ? node.getCpu() : 0;
        double load = node != null && node.getLoadAvg() != null ? node.getLoadAvg() : 0;
        return "{\"nodeCode\":\"" + nodeCode + "\",\"nodeMem\":" + fmt(mem)
                + ",\"nodeCpu\":" + fmt(cpu) + ",\"nodeLoad\":" + fmt(load)
                + ",\"concurrentTasks\":" + tel.concurrentTasks()
                + ",\"history7d\":" + tel.failureCount7d() + "}";
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private String fmt(double v) {
        if (v == Math.floor(v)) {
            return String.valueOf((long) v);
        }
        return String.valueOf(Math.round(v * 10) / 10.0);
    }
}
