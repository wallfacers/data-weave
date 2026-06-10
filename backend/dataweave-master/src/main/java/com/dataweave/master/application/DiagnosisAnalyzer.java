package com.dataweave.master.application;

import com.dataweave.master.domain.TaskDef;
import com.dataweave.master.domain.TaskInstance;
import com.dataweave.master.domain.WorkerNode;

/**
 * 失败根因分析器接口（自诊断推理接缝）。
 *
 * <p>MVP 用规则 mock 实现 {@link MockDiagnosisAnalyzer}；后期可替换为基于 LLM 的实现，
 * {@link DiagnosisService} 编排与产出结构不变。
 */
public interface DiagnosisAnalyzer {

    /**
     * 基于失败实例、所在节点与任务定义，产出根因分析结果。
     *
     * @param failed 失败的任务实例
     * @param node   该实例所在 worker 节点（可能为 null）
     * @param task   任务定义（可能为 null）
     */
    Analysis analyze(TaskInstance failed, WorkerNode node, TaskDef task);

    /**
     * 分析结果。
     *
     * @param title           简短标题
     * @param rootCause       根因结论文本
     * @param contextJson     采集到的上下文（JSON 字符串）
     * @param suggestionsJson 修复建议列表（JSON 数组字符串，每项含 action/label）
     */
    record Analysis(String title, String rootCause, String contextJson, String suggestionsJson) {
    }
}
