# Specification Quality Checklist: 调度器节点容错闭环（节点剔除·任务转移·不丢不失败）

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-07-10
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

- **Clarify Session 2026-07-10（4 问已整合，16 项仍全过无回归）**：Q1 毒任务单实例重派上限→挂起+告警（FR-012）；Q2 max-runtime 纳入本特性（FR-020）；Q3 worker 自我中止防分区双跑（FR-021）；Q4 外部托管长驻作业（Flink 流式）reattach-不重提交 + 外部作业句柄（FR-022~026，为未来实时任务卡片预留挂载点）。
- 三个用户故事按 P1/P2/P3 优先级分层，各自可独立测试、独立交付：US1（坏节点剔除+转移，MVP）、US2（无节点安全等待+恢复抽干）、US3（长跑/流式/丢失区分+人工兜底）。
- FR-011 刻意保持"下发纪元令牌"为抽象概念（对应实现里的 `attempt` 栅栏），不写具体列名，符合"无实现细节"约束；Key Entities 也只描述职责而非表结构。
- 数值参数（稳定窗、熔断阈值、退避、告警阈值、续约窗）在 Assumptions 中声明为"保守默认 + 可配置"，避免在 spec 阶段过早固化；具体值留待 plan/实测。
- 幽灵 worker-local 的部署侧修复（compose 关内嵌 worker）已在基线 `2b137e5` 落地，属本特性范围之外的互补项，已在 Assumptions 注明，避免范围混淆。
