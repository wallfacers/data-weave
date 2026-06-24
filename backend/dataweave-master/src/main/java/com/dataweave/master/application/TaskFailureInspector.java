package com.dataweave.master.application;

import com.dataweave.master.domain.Finding;
import com.dataweave.master.domain.TaskInstance;
import com.dataweave.master.domain.TaskInstanceRepository;
import com.dataweave.master.domain.TaskDiagnosis;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 失败巡检器（主动发现首发 Inspector）。
 *
 * <p>扫描 FAILED 任务实例，对尚无对应 Finding 的实例调用现有 {@link DiagnosisService#diagnoseInstance}
 * （幂等、复用诊断引擎，不重写），并把产出的 {@link TaskDiagnosis} 映射为统一 {@link Finding}（source=TASK_FAILURE）。
 * 已处理过的实例（已存在任意状态的 Finding）跳过，避免修复后重复举手。
 */
@Component
public class TaskFailureInspector implements Inspector {

    static final String SOURCE = "TASK_FAILURE";
    static final String TARGET_TYPE = "TASK_INSTANCE";

    private final TaskInstanceRepository instanceRepository;
    private final DiagnosisService diagnosisService;
    private final FindingService findingService;
    private final ObjectMapper objectMapper;

    public TaskFailureInspector(TaskInstanceRepository instanceRepository,
                                DiagnosisService diagnosisService,
                                FindingService findingService,
                                ObjectMapper objectMapper) {
        this.instanceRepository = instanceRepository;
        this.diagnosisService = diagnosisService;
        this.findingService = findingService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public List<Finding> inspect() {
        List<Finding> out = new ArrayList<>();
        for (TaskInstance inst : instanceRepository.findByState("FAILED")) {
            if (inst.getId() == null) {
                continue;
            }
            String targetId = inst.getId().toString();
            if (findingService.exists(SOURCE, TARGET_TYPE, targetId)) {
                continue; // 已处理过（含已修复）——不重复举手
            }
            TaskDiagnosis diag = diagnosisService.diagnoseInstance(inst.getId());
            out.add(toFinding(targetId, diag));
        }
        return out;
    }

    /** 把诊断结果映射为统一 Finding；suggestions {action,label} → actions {key,label,actionType}。 */
    private Finding toFinding(String targetId, TaskDiagnosis diag) {
        Finding f = new Finding();
        f.setSource(SOURCE);
        f.setSeverity("CRITICAL");
        f.setTargetType(TARGET_TYPE);
        f.setTargetId(targetId);
        f.setTitle(diag.getTitle());
        f.setRootCause(diag.getRootCause());
        f.setEvidenceJson(diag.getContextJson());
        f.setActionsJson(mapActions(diag.getSuggestionsJson()));
        f.setTaskDiagnosisId(diag.getId());
        f.setTenantId(diag.getTenantId());
        f.setProjectId(diag.getProjectId());
        return f;
    }

    /**
     * 诊断的 suggestionsJson（{@code [{"action":"RERUN_MORE_MEMORY","label":"…"}]}）→
     * 统一 actionsJson（{@code [{"key":"…","label":"…","actionType":"APPLY_FIX_…"}]}）。
     * 解析失败时回退一个默认 RERUN 动作，保证修复入口不缺失。
     */
    String mapActions(String suggestionsJson) {
        List<Map<String, String>> actions = new ArrayList<>();
        try {
            if (suggestionsJson != null && !suggestionsJson.isBlank()) {
                JsonNode arr = objectMapper.readTree(suggestionsJson);
                if (arr.isArray()) {
                    for (JsonNode node : arr) {
                        String key = text(node, "key", text(node, "action", null));
                        if (key == null || key.isBlank()) {
                            continue;
                        }
                        String label = text(node, "label", key);
                        String actionType = text(node, "actionType", "APPLY_FIX_" + key.toUpperCase());
                        Map<String, String> a = new LinkedHashMap<>();
                        a.put("key", key);
                        a.put("label", label);
                        a.put("actionType", actionType);
                        actions.add(a);
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // 解析异常 → 落到下方默认动作
        }
        if (actions.isEmpty()) {
            Map<String, String> rerun = new LinkedHashMap<>();
            rerun.put("key", "RERUN");
            rerun.put("label", "重跑");
            rerun.put("actionType", "APPLY_FIX_RERUN");
            actions.add(rerun);
        }
        return objectMapper.writeValueAsString(actions);
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode v = node.get(field);
        return v != null && !v.isNull() ? v.asString() : fallback;
    }
}
