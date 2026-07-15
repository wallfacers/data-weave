# 宪法修订案（草案 · 待用户批准）— Principle IV 重定义

> **状态**：DRAFT。未经用户批准前，本案不落地到 `.specify/memory/constitution.md`；
> 069 与 Principle IV 现文的张力在 `plan.md` Complexity Tracking 中如实记录。
> 批准后方执行 MAJOR bump（1.2.0 → 2.0.0）+ 模板传播。

## 触发

069「任务失败智能运维」在**服务端**编排 LLM（`IncidentAgentService` 诊断/修复提案/`IncidentConversationService`
对话/`IncidentBriefingService` 播报）。这与 Principle IV 现文的**不可让渡内核 #1「服务端无 AI 大脑」**直接冲突——
按现文，069 的服务端 LLM 编排属违宪。需在原则层面裁决：要么否决 069，要么重定义边界。

## 修订主张（重定义，非稀释）

把 Principle IV 的「服务端无 AI 大脑」从**绝对禁令**收敛为**分层边界**：区分两类 AI 用途，各有不同约束。

### 第一层：创作 AI（Authoring）— 归位本地，**一字不改**

任务/任务流的**创作**（写代码、改脚本、设计 DAG）AI 能力仍**排他地**由开发者本地 agent 提供
（Claude Code / Codex / 兼容者），经 Skill + dw CLI 的 golden path。服务端**绝不**嵌入创作 agent、
不嵌入 IntentRouter/chat cockpit/proactive-notify。此层的三条旧内核**原样保留**。

### 第二层：Trust 层运维编队（Ops Orchestration）— 允许服务端**有界**存在

**运行态故障响应**（诊断分型、梯度处置、升级裁决、值班播报）的 AI 编排，允许在服务端以**受约束**方式存在，
因为它天然需要贴近调度内核的实时事实（实例状态、日志、心跳），且必须 7×24 无人值守——本地 agent 不在场时
仍要工作。此层受**三条不可让渡内核等价物**约束（与旧三条同构，非放水）：

1. **LLM 只做分类与文本生成，绝不拥有处置决策**。所有「改什么/重跑/调资源/升级」的路径判定由确定性
   Java 代码（`RemediationPlanner` 梯度映射）拥有；LLM 输出只喂决策的输入，不直接触发副作用。
   （对应旧 #1「服务端无 AI 大脑」——大脑=决策权仍不在服务端 LLM 手里。）
2. **一切副作用过既有闸门，零绕过**。事故处置的每个写动作 → `ActionRequest` → `GatedActionService` →
   `PolicyEngine`（L2/L3 人审）+ `agent_action` 审计；代码修复走 L3 人审 + 二次确认发布。
   （对应旧 #2「AI 能力由本地 agent 提供」的治理等价：服务端 AI 无自主权，人类/策略是最终闸门。）
3. **对调度内核零侵入 + 观测不损伤**。巡检只读观察 `task_instance`，绝不反向锁行/改状态机/动锁序
   （守 SC-008 与 060 七红线）；ops overview/metrics/run logs/DAG 视图不受损。
   （对应旧 #3 原样。）

**边界护栏**（防止第二层膨胀成「服务端全能 agent」）：
- 第二层只服务**运行态故障响应**，不得扩张到任务创作/定义写入（创作写入仍只经 `project_push`）。
- 第二层的 AI 配置复用第一层同一租户级 Agent 配置通道（不新建独立 AI 栈），且可一键停用（FR-012）。
- 第二层不得成为 MCP 创作能力的旁路（MCP 仍只读 + reverify 过闸门）。

## 版本与传播

- **MAJOR**：1.2.0 → **2.0.0**（原则语义向后不兼容——从「绝对禁令」改为「分层边界」）。
- **传播**：`plan-template.md` 的 Constitution Check for IV 增加第二层判据；
  `spec-template`/`tasks-template` 无需改；本仓 CLAUDE.md 已含 069 Knowledge Map 行。
- **Sync Impact Report**：批准后写入 constitution.md 头部（同既有 1.1.0/1.2.0 报告体例）。

## 待用户裁决

- [ ] 批准本重定义（执行 MAJOR bump + 传播）
- [ ] 否决（则 069 服务端 LLM 编排需下线或改由本地 agent 驱动——推翻 US4 直播/自愈架构）
- [ ] 修改后批准（用户指定边界调整）
