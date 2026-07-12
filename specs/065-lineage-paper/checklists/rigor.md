# Checklist: 研究严谨性——需求质量（可发表性单元测试）

**Purpose**: 以"英文的单元测试"校验 spec 中方法学需求的书写质量（完整/清晰/一致/可度量/覆盖），而非验证实现是否跑通
**Created**: 2026-07-12
**Feature**: [spec.md](../spec.md)
**Depth**: 正式发表门 | **Audience**: 作者 + 同行评审 | **Focus**: 统计严谨 · 诚实反挑数据 · 可复现边界 · 工具基线

## Requirement Completeness（需求是否齐全）

- [ ] CHK001 - 是否对论文将做的**每一条**比较类头条陈述都定义了显著性检验需求？[Completeness, Spec §FR-002/§FR-010]
- [ ] CHK002 - 当某子集样本极少时，置信区间的呈现需求是否有定义（宽区间如实呈现）？[Completeness, Edge Case, Spec §Edge Cases]
- [ ] CHK003 - 工具基线集合是否被完整枚举（哪些工具、跑在哪些数据集上）？[Completeness, Spec §FR-004]
- [ ] CHK004 - 可复现发布物的内容是否被完整规定（含什么/不含什么）？[Completeness, Spec §FR-007]
- [ ] CHK005 - 泄漏曲线与"合成 vs 真实塌缩"作为可复现招牌图，是否有对应的产出需求（而非仅在 SC 提及）？[Gap, Spec §SC-005]
- [ ] CHK006 - 分层信封（C3）作为头条第三支，是否有对应的证据产出需求，还是仅靠既有产物？[Gap, Spec §背景]

## Requirement Clarity（术语是否量化、无歧义）

- [ ] CHK007 - "显著/significant"是否以明确判据量化（p 阈值 / CI 不含 0）？[Clarity, Spec §Clarifications/§SC-003]
- [ ] CHK008 - 工具在脚本上的"≈0/结构性失效"是否有数值界（recall ≤0.10）且全文一致引用？[Clarity, Spec §SC-003]
- [ ] CHK009 - "适度扩容/modest expansion"是否量化了目标样本量？[Ambiguity, Spec §FR-009/§US4]
- [ ] CHK010 - "命令式脚本子集 vs 纯 SQL 子集"是否有显式的样本归类判据（一个样本如何被判入 sql/script）？[Clarity, Gap, Spec §FR-004]
- [ ] CHK011 - "teacher"是否消歧到具体模型（deepseek 缺失下，配对检验对手究竟是谁）？[Clarity, Spec §Assumptions]
- [ ] CHK012 - "与既有报告可比/comparable"（SQLLineage@SQL）是否给出可比的容差口径？[Clarity, Ambiguity, Spec §SC-003]

## Requirement Consistency（需求间是否自洽）

- [ ] CHK013 - 工具基线集合在 §FR-004、§SC-003、§Assumptions 三处是否一致（regex + SQLLineage，不含 Calcite）？[Consistency]
- [ ] CHK014 - 发布边界在 §FR-007、§Assumptions 与 benchmark manifest 契约三处是否一致（标签+指针、不含源码正文/合成集）？[Consistency]
- [ ] CHK015 - "模型召回不设硬下限"（Clarify Q3）是否与 §SC-003 的成功判据自洽（无隐藏最低分门槛）？[Consistency, Spec §SC-003]
- [ ] CHK016 - 诚实性需求（§FR-010/§FR-011）是否与"反挑数据"边界情形一致对齐？[Consistency, Spec §Edge Cases]

## Acceptance Criteria Quality（成功判据是否可客观度量）

- [ ] CHK017 - SC-001（头条陈述 100% 带 CI/显著性）能否对照论文草稿客观核验？[Measurability, Spec §SC-001]
- [ ] CHK018 - SC-004 的"第三方一次尝试内复算"是否可度量、"一次尝试"是否有定义？[Measurability, Spec §SC-004]
- [ ] CHK019 - SC-006 的"CI 宽度相对收窄"是否给出了用以对比的基准参照？[Measurability, Spec §SC-006]
- [ ] CHK020 - "模型显著高于工具"是否可仅凭 spec 判据（配对 p / 差值 CI）客观判定，无需读实现？[Measurability, Spec §SC-003]

## Scenario & Coverage（各类情形是否被需求覆盖）

- [ ] CHK021 - 是否为"显著性检验不结论（n≈49 的预期结果）"定义了必须如实报告的需求？[Coverage, Spec §SC-002]
- [ ] CHK022 - 是否为配对检验的并列（tie）情形定义了处理需求？[Coverage, Edge Case, Spec §Edge Cases]
- [ ] CHK023 - 是否为"工具在脚本上偶发非零"定义了如实报告（不为叙事清零）的需求？[Coverage, Edge Case, Spec §Edge Cases]
- [ ] CHK024 - 受限凭据（deepseek）门控的评测分支是否被明确从核心可复现路径分离？[Coverage, Spec §FR-008]

## Non-Functional：确定性 / 可复现 / 合规

- [ ] CHK025 - 所有统计输出的确定性需求（固定 seed → 逐位一致）是否被明确规定？[Non-Functional, Spec §FR-001]
- [ ] CHK026 - 发布源指针的许可/重分发约束是否作为需求被记录（而非只在理据里）？[Compliance, Spec §FR-007]
- [ ] CHK027 - "核心复现无需私有凭据"是否可被客观测试（如无凭据环境跑通）？[Measurability, Spec §FR-008]

## Dependencies & Assumptions（依赖与假设是否成文可溯）

- [ ] CHK028 - "三个核心发现已合入 main 且有效"这一假设是否可溯源/可核验？[Assumption, Spec §Assumptions]
- [ ] CHK029 - 对仲裁后 gold C（gitignored、磁盘不存在）的依赖是否记录了获取路径？[Dependency, Gap, Spec §Assumptions]
- [ ] CHK030 - MSR 投稿周期 DDL 依赖是否成文，且其对 W4/US4 取舍的影响是否被界定？[Assumption, Spec §Assumptions]

## Ambiguities & Boundaries（out-of-scope 边界是否清晰）

- [ ] CHK031 - 列级血缘 out-of-scope 边界是否表述得无歧义且一致（future work，而非部分声称）？[Clarity, Spec §FR-011]
- [ ] CHK032 - W4/US4 究竟是"范围内可选"还是"范围外"，其触发条件是否明确？[Ambiguity, Spec §US4/§FR-009]
