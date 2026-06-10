package com.dataweave.master.application;

import com.dataweave.master.domain.TaskDef;
import com.dataweave.master.domain.TaskDefRepository;
import com.dataweave.master.domain.TaskDiagnosis;
import com.dataweave.master.domain.TaskInstance;
import com.dataweave.master.domain.TaskInstanceRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * 调度运维 / 驾驶舱查询服务：任务定义、运行实例、全局运行概况。
 */
@Service
public class OpsService {

    private final TaskDefRepository taskDefRepository;
    private final TaskInstanceRepository instanceRepository;
    private final DiagnosisService diagnosisService;

    public OpsService(TaskDefRepository taskDefRepository,
                      TaskInstanceRepository instanceRepository,
                      DiagnosisService diagnosisService) {
        this.taskDefRepository = taskDefRepository;
        this.instanceRepository = instanceRepository;
        this.diagnosisService = diagnosisService;
    }

    /** 所有任务定义，按 id 升序。 */
    public List<TaskDef> tasks() {
        List<TaskDef> list = new ArrayList<>();
        taskDefRepository.findAll().forEach(list::add);
        list.sort(Comparator.comparing(TaskDef::getId, Comparator.nullsLast(Comparator.naturalOrder())));
        return list;
    }

    /**
     * 正式运行实例（runMode=="NORMAL"，排除 TEST 试跑），按 id 降序。
     */
    public List<TaskInstance> instances() {
        return StreamSupport.stream(instanceRepository.findAll().spliterator(), false)
                .filter(i -> "NORMAL".equals(i.getRunMode()))
                .sorted(Comparator.comparing(TaskInstance::getId, Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());
    }

    /** 失败的正式运行实例（state==FAILED && runMode==NORMAL）。 */
    public List<TaskInstance> failedInstances() {
        return instanceRepository.findByState("FAILED").stream()
                .filter(i -> "NORMAL".equals(i.getRunMode()))
                .collect(Collectors.toList());
    }

    /** 驾驶舱全局态势。 */
    public DashboardSummary summary() {
        List<TaskInstance> all = instances();
        int success = 0;
        int failed = 0;
        int running = 0;
        for (TaskInstance i : all) {
            String s = i.getState() == null ? "" : i.getState();
            switch (s) {
                case "SUCCESS" -> success++;
                case "FAILED" -> failed++;
                case "RUNNING" -> running++;
                default -> {
                }
            }
        }
        return new DashboardSummary(all.size(), success, failed, running,
                failedInstances(), diagnosisService.open());
    }

    /**
     * 驾驶舱概况。
     *
     * @param total           正式实例总数
     * @param success         成功数
     * @param failed          失败数
     * @param running         运行中数
     * @param failedInstances 失败实例清单
     * @param diagnosing      待处理（Agent 诊断中）的诊断清单
     */
    public record DashboardSummary(int total, int success, int failed, int running,
                                   List<TaskInstance> failedInstances, List<TaskDiagnosis> diagnosing) {
    }
}
