package com.dataweave.master.application;

import com.dataweave.master.domain.TaskDef;
import com.dataweave.master.domain.TaskDefRepository;
import com.dataweave.master.domain.TaskDiagnosis;
import com.dataweave.master.domain.TaskDiagnosisRepository;
import com.dataweave.master.domain.TaskInstance;
import com.dataweave.master.domain.TaskInstanceRepository;
import com.dataweave.master.domain.WorkerNode;
import com.dataweave.master.domain.WorkerNodeRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 失败自诊断服务：采集失败实例上下文 → 调 {@link DiagnosisAnalyzer} 产出根因+建议 → 持久化；
 * 并支持对建议的一键修复执行。
 *
 * <p>修复执行已迁入 {@link GatedActionService} 闸门（agent-fabric-m1 收口缺口③）：applyFix 不再直接执行，
 * 而是构造 {@link ActionRequest} 经 PolicyEngine 裁决 + agent_action 留痕，真实动作由
 * {@link DefaultPlatformActionExecutor} 执行。
 */
@Service
public class DiagnosisService {

    private final TaskInstanceRepository instanceRepository;
    private final TaskDefRepository taskDefRepository;
    private final WorkerNodeRepository nodeRepository;
    private final TaskDiagnosisRepository diagnosisRepository;
    private final DiagnosisAnalyzer analyzer;
    private final GatedActionService gatedActionService;

    public DiagnosisService(TaskInstanceRepository instanceRepository,
                            TaskDefRepository taskDefRepository,
                            WorkerNodeRepository nodeRepository,
                            TaskDiagnosisRepository diagnosisRepository,
                            DiagnosisAnalyzer analyzer,
                            GatedActionService gatedActionService) {
        this.instanceRepository = instanceRepository;
        this.taskDefRepository = taskDefRepository;
        this.nodeRepository = nodeRepository;
        this.diagnosisRepository = diagnosisRepository;
        this.analyzer = analyzer;
        this.gatedActionService = gatedActionService;
    }

    /** 诊断最近一条失败实例；无失败实例则返回空。 */
    public Optional<TaskDiagnosis> diagnoseLatestFailure() {
        return instanceRepository.findFirstByStateOrderByIdDesc("FAILED")
                .map(inst -> diagnoseInstance(inst.getId()));
    }

    /**
     * 诊断指定实例：已有诊断则幂等返回；否则采集上下文 + 分析 + 落库。
     */
    public TaskDiagnosis diagnoseInstance(UUID taskInstanceId) {
        Optional<TaskDiagnosis> existing =
                diagnosisRepository.findFirstByTaskInstanceIdOrderByIdDesc(taskInstanceId);
        if (existing.isPresent()) {
            return existing.get();
        }

        TaskInstance instance = instanceRepository.findById(taskInstanceId).orElse(null);
        WorkerNode node = instance != null && instance.getWorkerNodeCode() != null
                ? nodeRepository.findByNodeCode(instance.getWorkerNodeCode()).orElse(null) : null;
        TaskDef task = instance != null && instance.getTaskId() != null
                ? taskDefRepository.findById(instance.getTaskId()).orElse(null) : null;

        DiagnosisAnalyzer.Analysis analysis = analyzer.analyze(instance, node, task);

        LocalDateTime now = LocalDateTime.now();
        TaskDiagnosis diagnosis = new TaskDiagnosis();
        diagnosis.setTenantId(1L);          // MVP 默认租户/项目
        diagnosis.setProjectId(1L);         // MVP 默认租户/项目
        diagnosis.setTaskInstanceId(taskInstanceId);
        diagnosis.setWorkflowInstanceId(instance != null ? instance.getWorkflowInstanceId() : null);
        diagnosis.setTaskId(instance != null ? instance.getTaskId() : null);
        diagnosis.setWorkerNodeCode(instance != null ? instance.getWorkerNodeCode() : null);
        diagnosis.setTitle(analysis.title());
        diagnosis.setRootCause(analysis.rootCause());
        diagnosis.setContextJson(analysis.contextJson());
        diagnosis.setSuggestionsJson(analysis.suggestionsJson());
        diagnosis.setStatus("OPEN");
        diagnosis.setCreatedAt(now);
        diagnosis.setUpdatedAt(now);
        diagnosis.setDeleted(0);
        diagnosis.setVersion(0);
        return diagnosisRepository.save(diagnosis);
    }

    /** 所有诊断记录，按 id 降序。 */
    public List<TaskDiagnosis> all() {
        List<TaskDiagnosis> list = new ArrayList<>();
        diagnosisRepository.findAll().forEach(list::add);
        list.sort(Comparator.comparing(TaskDiagnosis::getId, Comparator.nullsLast(Comparator.reverseOrder())));
        return list;
    }

    /** 仍待处理（未修复）的诊断，供驾驶舱「Agent 诊断中」区块展示。 */
    public List<TaskDiagnosis> open() {
        return diagnosisRepository.findByStatus("OPEN");
    }

    public Optional<TaskDiagnosis> get(Long id) {
        return diagnosisRepository.findById(id);
    }

    /** UI 默认入口（操作者为右舷用户）。 */
    public FixResult applyFix(Long diagnosisId, String action) {
        return applyFix(diagnosisId, action, "ui-user", "UI");
    }

    /**
     * 执行修复建议，经 PolicyEngine 闸门。dev 环境 RERUN 类按 L1 直执行并落痕；
     * 若被裁决为审批/拒绝，返回相应反馈（success=false）。
     *
     * @param action RERUN / MIGRATE_NODE / RERUN_MORE_MEMORY / CAP_NODE_WEIGHT
     */
    public FixResult applyFix(Long diagnosisId, String action, String actor, String actorSource) {
        TaskDiagnosis diagnosis = diagnosisRepository.findById(diagnosisId).orElse(null);
        if (diagnosis == null) {
            return new FixResult(false, "未找到诊断记录 #" + diagnosisId, null);
        }
        String act = action == null ? "RERUN" : action.trim().toUpperCase();
        String summary = "一键修复 " + act + " · 诊断 #" + diagnosisId
                + (diagnosis.getWorkerNodeCode() != null ? " · " + diagnosis.getWorkerNodeCode() : "");

        ActionRequest req = ActionRequest.builder()
                .toolName("apply_fix")
                .actionType("APPLY_FIX_" + act)
                .targetType("DIAGNOSIS")
                .targetId(String.valueOf(diagnosisId))
                .ownedByPlatform(true)
                .actor(actor)
                .actorSource(actorSource)
                .summary(summary)
                .build();

        GateResult gr = gatedActionService.submit(req);
        return new FixResult(gr.executed(), gr.message(), gr.resultInstanceId());
    }

    /**
     * 修复执行结果。
     *
     * @param success       是否成功触发
     * @param message       面向用户的反馈
     * @param newInstanceId 若产生了重跑实例，其 id
     */
    public record FixResult(boolean success, String message, UUID newInstanceId) {
    }
}
