# Specification Quality Checklist: 抗泄漏消融（041-R 方案 B）

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-07-05
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic (no implementation details)
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

## Notes

- **关于"No implementation details"的判定**：本特性是一个**科研消融实验**，其交付物本身是 ML 训练/评测产物。spec 中对既有资产（`eval_real.py`、`leak_analysis.py`、`real.jsonl`、`weft-lineage-weights/` 等）的引用**不是实现选择，而是可比性约束**——041-R 已冻结这套评测口径与金标，B 必须原样复用才能与基线逐列可比。故这些命名属于"必须复用的固定约束"，非"实现细节泄漏"，判定通过。训练/数据变体的**具体做法**（注入算法、配比调参、代码结构）保留给 `/speckit-plan`，spec 只规定 WHAT（消融维度、可比性、诚实披露）与 WHY（把论文从"失败"升级为"补救也失败"）。
- **成功定义的特殊性**：成功 = 决定性且诚实的测量，**非补救必须奏效**。三种产出（修不动 / 部分修好 / 翻转结论）均为合格交付，前提是披露诚实。此已在背景、SC 引言、SC-006 与 Edge Cases 中固化。
- 无 [NEEDS CLARIFICATION]：所有空白均基于 041-R 既有设计做了有据的默认（真实表名来源、配比策略留 plan 细化，但来源边界与可复现约束已在 FR/Assumptions 中定死）。
- 校验结论：全部通过，可进入 `/speckit-plan`（建议先 `/speckit-clarify` 复核 B1 注入策略/B2 负样本配比两处 plan 级设计点，但非 spec 门槛）。
