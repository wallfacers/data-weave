package com.dataweave.master.application;

import com.dataweave.master.domain.TaskDef;
import com.dataweave.master.domain.TaskInstance;
import com.dataweave.master.domain.WorkerNode;

import java.util.Locale;

/**
 * 失败根因分析器接口（自诊断推理接缝）。
 *
 * <p>MVP 用规则 mock 实现 {@link MockDiagnosisAnalyzer}；后期可替换为基于 LLM 的实现，
 * {@link DiagnosisService} 编排与产出结构不变。
 */
public interface DiagnosisAnalyzer {

    /** 默认 locale（中文）分析。 */
    default Analysis analyze(TaskInstance failed, WorkerNode node, TaskDef task) {
        return analyze(failed, node, task, Telemetry.EMPTY, Locale.SIMPLIFIED_CHINESE);
    }

    /**
     * 基于失败实例、所在节点、任务定义与 master 端真采集遥测，产出根因分析结果。
     *
     * @param failed    失败的任务实例
     * @param node      该实例所在 worker 节点（可能为 null）
     * @param task      任务定义（可能为 null）
     * @param telemetry master 端聚合的真实并发争抢数与失败 history（live-telemetry L1）
     * @param locale    本地化 title / rootCause / suggestions.label 用
     */
    Analysis analyze(TaskInstance failed, WorkerNode node, TaskDef task, Telemetry telemetry, Locale locale);

    /**
     * master 端真采集遥测（替换信任 worker 上报 / 硬编码）。
     *
     * @param concurrentTasks 该节点当前并发运行实例数（DISPATCHED/RUNNING）
     * @param failureCount7d  近 7 天该任务×该节点的失败次数
     */
    record Telemetry(int concurrentTasks, int failureCount7d) {
        public static final Telemetry EMPTY = new Telemetry(0, 0);
    }

    /**
     * 分析结果。
     *
     * @param title           简短标题（已按 locale 本地化）
     * @param rootCause       根因结论文本（已按 locale 本地化）
     * @param contextJson     采集到的上下文（JSON 字符串）
     * @param suggestionsJson 修复建议列表（JSON 数组字符串，每项含 action/label，label 已按 locale 本地化）
     */
    record Analysis(String title, String rootCause, String contextJson, String suggestionsJson) {
    }
}
