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

/**
 * 失败自诊断服务：采集失败实例上下文 → 调 {@link DiagnosisAnalyzer} 产出根因+建议 → 持久化；
 * 并支持对建议的一键修复执行（需用户确认后由上层调用）。
 */
@Service
public class DiagnosisService {

    private final TaskInstanceRepository instanceRepository;
    private final TaskDefRepository taskDefRepository;
    private final WorkerNodeRepository nodeRepository;
    private final TaskDiagnosisRepository diagnosisRepository;
    private final DiagnosisAnalyzer analyzer;
    private final FleetService fleetService;

    public DiagnosisService(TaskInstanceRepository instanceRepository,
                            TaskDefRepository taskDefRepository,
                            WorkerNodeRepository nodeRepository,
                            TaskDiagnosisRepository diagnosisRepository,
                            DiagnosisAnalyzer analyzer,
                            FleetService fleetService) {
        this.instanceRepository = instanceRepository;
        this.taskDefRepository = taskDefRepository;
        this.nodeRepository = nodeRepository;
        this.diagnosisRepository = diagnosisRepository;
        this.analyzer = analyzer;
        this.fleetService = fleetService;
    }

    /** 诊断最近一条失败实例；无失败实例则返回空。 */
    public Optional<TaskDiagnosis> diagnoseLatestFailure() {
        return instanceRepository.findFirstByStateOrderByIdDesc("FAILED")
                .map(inst -> diagnoseInstance(inst.getId()));
    }

    /**
     * 诊断指定实例：已有诊断则幂等返回；否则采集上下文 + 分析 + 落库。
     */
    public TaskDiagnosis diagnoseInstance(Long taskInstanceId) {
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

    /**
     * 执行修复建议（一键修复，需上层在用户确认后调用）。MVP 以 mock 推进体现效果：
     * 重跑类 → 产生一条成功实例；迁移类 → 落到最空闲节点重跑；限权重 → 标记并提示。
     * 执行后把诊断置 RESOLVED。
     */
    public FixResult applyFix(Long diagnosisId, String action) {
        TaskDiagnosis diagnosis = diagnosisRepository.findById(diagnosisId).orElse(null);
        if (diagnosis == null) {
            return new FixResult(false, "未找到诊断记录 #" + diagnosisId, null);
        }

        String act = action == null ? "RERUN" : action.toUpperCase();
        String message;
        Long newInstanceId = null;

        switch (act) {
            case "MIGRATE_NODE" -> {
                String target = fleetService.pickLeastLoadedOnline()
                        .map(WorkerNode::getNodeCode).orElse("node-online");
                TaskInstance inst = rerunOnNode(diagnosis, target, "[fix] 迁移到 " + target + " 后重跑成功");
                newInstanceId = inst.getId();
                message = "已将任务迁移到空闲节点 " + target + " 重跑，运行成功。";
            }
            case "RERUN_MORE_MEMORY" -> {
                String nodeCode = diagnosis.getWorkerNodeCode() != null
                        ? diagnosis.getWorkerNodeCode() : "node-1";
                TaskInstance inst = rerunOnNode(diagnosis, nodeCode, "[fix] 调大 executor 内存后重跑成功");
                newInstanceId = inst.getId();
                message = "已调大 executor 内存并在 " + nodeCode + " 重跑，运行成功。";
            }
            case "CAP_NODE_WEIGHT" -> message = "已为节点 " + diagnosis.getWorkerNodeCode()
                    + " 设置调度权重上限，后续将减少该节点的任务并发（mock 生效）。";
            default -> {
                String nodeCode = diagnosis.getWorkerNodeCode() != null
                        ? diagnosis.getWorkerNodeCode() : "node-1";
                TaskInstance inst = rerunOnNode(diagnosis, nodeCode, "[fix] 原地重跑成功");
                newInstanceId = inst.getId();
                message = "已原地重跑，运行成功。";
            }
        }

        diagnosis.setStatus("RESOLVED");
        diagnosis.setUpdatedAt(LocalDateTime.now());
        diagnosisRepository.save(diagnosis);
        return new FixResult(true, message, newInstanceId);
    }

    private TaskInstance rerunOnNode(TaskDiagnosis diagnosis, String nodeCode, String log) {
        LocalDateTime now = LocalDateTime.now();
        TaskInstance inst = new TaskInstance();
        inst.setTenantId(1L);   // MVP 默认租户/项目
        inst.setProjectId(1L);  // MVP 默认租户/项目
        inst.setTaskId(diagnosis.getTaskId());
        inst.setRunMode("NORMAL");
        inst.setState("SUCCESS");
        inst.setAttempt(1);
        inst.setWorkerNodeCode(nodeCode);
        inst.setStartedAt(now);
        inst.setFinishedAt(now);
        inst.setLog(log);
        inst.setCreatedAt(now);
        inst.setUpdatedAt(now);
        inst.setDeleted(0);
        inst.setVersion(0L);
        return instanceRepository.save(inst);
    }

    /**
     * 修复执行结果。
     *
     * @param success       是否成功触发
     * @param message       面向用户的反馈
     * @param newInstanceId 若产生了重跑实例，其 id
     */
    public record FixResult(boolean success, String message, Long newInstanceId) {
    }
}
