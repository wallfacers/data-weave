/**
 * 058 数据开发 LSP —— 血缘/依赖接地的创作上下文服务（编排型，纯读 + 诊断，确定性零 LLM）。
 *
 * <p>本包为 CLI vibecoding 回路里的 AI 编码 agent 提供按需的血缘/依赖接地能力，
 * 编排复用既有 {@code LineageQueryService}/{@code ScriptLineageService}/{@code CatalogGroundingService}/
 * {@code WorkflowEdgeRepository}，<b>不</b>实现第二套血缘抽取或图存储（宪法 III/V 派生的硬不变量）。
 *
 * @see com.dataweave.master.application.authoring.AuthoringContextService
 */
package com.dataweave.master.application.authoring;
