package com.dataweave.master.application.lineage.script;

/**
 * 脚本血缘抽取器可插拔契约（041 FR-010）。
 *
 * <p>实现以 Spring {@code @Component} 注入 {@code ScriptLineageService} 聚合；新增通道
 * （规则/模型/未来形态）= 新增一个实现，存储与展示层零改动。三通道内置实现：
 * {@code EmbeddedSqlExtractor}（SCRIPT_SQL）、{@code ApiPatternExtractor}（SCRIPT_INFERRED）、
 * {@code ModelExtractor}（SCRIPT_MODEL）。
 *
 * <p>契约约束：{@link #extract} 绝不抛异常拖垮主链路（内部降级为空产物 + hint 留痕，FR-005）；
 * 宁缺毋滥——不确定的目标记 {@link ScriptExtraction.Hint} 而非猜测出边（FR-006）。
 */
public interface ScriptLineageExtractor {

    /** 是否处理该任务类型；亦承担运行期可用性开关（如模型通道 endpoint 未配置 → false 整体旁路）。 */
    boolean supports(String taskType);

    /** 名字级抽取；实现内部自行捕获一切异常并降级。 */
    ScriptExtraction extract(ScriptSource source);
}
