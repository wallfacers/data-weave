# Implementation Plan: 运维任务流 DAG 查看器

**Branch**: `002-ops-dag-viewer` | **Date**: 2026-06-26 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/002-ops-dag-viewer/spec.md`

## Summary

在 Ops 中心的周期/手动任务流列表中，将当前的"查看 DAG"按钮行为从"跳转到开发画布 Tab"改为"打开大弹框展示已发布版本的只读 DAG 图"。后端新增一个端点返回发布版本 DAG 快照数据；前端新增一个只读 DAG 弹框组件，复用现有 `@xyflow/react` 节点渲染和 `@base-ui/react/dialog` 弹框模式。

## Technical Context

**Language/Version**: Java 25 (backend), TypeScript 5 + Next.js 16 (frontend)

**Primary Dependencies**: Spring Boot 4.0 / WebFlux (backend), `@xyflow/react` v12 (前端 DAG 渲染), `@base-ui/react/dialog` (弹框原语), Zustand (状态管理)

**Storage**: PostgreSQL — `workflow_def_version.dag_snapshot_json` 列存储发布 DAG 快照 JSON

**Testing**: JUnit 5 + AssertJ (后端), vitest (前端), Playwright E2E (浏览器验证闸门)

**Target Platform**: Linux server (backend, port 8000), Web 浏览器 (frontend, port 4000)

**Project Type**: Web application — Spring Boot backend + Next.js 16 frontend

**Performance Goals**: 弹框打开到 DAG 完整渲染 ≤ 2s（50 节点以内）；100 节点下缩放/平移 ≥ 30fps

**Constraints**: 弹框不得触发页面跳转、不得切换 Workspace Tab；DAG 图只读（无编辑/拖拽创建/运行操作）

**Scale/Scope**: 单次弹框展示 ≤ 200 节点、≤ 500 条边；两个列表面板各加一个操作入口

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

Constitution 为模板占位符，无项目实际约束规则。以下为本项目 CLAUDE.md 中相关规则检查：

| 规则 | 状态 | 说明 |
|------|------|------|
| 依赖方向 (domain ← application ← infrastructure ← interfaces) | ✅ 合规 | 新端点只在 interfaces 层，domain 实体不变，application 层只加查询方法 |
| i18n — 静态 UI 文案 → 前端 i18n | ✅ 合规 | "查看 DAG"按钮/弹框标题/空状态/错误提示均通过 `t()` 走前端 i18n |
| i18n — 后端生成文案 → Messages.get | ✅ 合规 | 后端错误信息通过 BizException 走 GlobalExceptionHandler 本地化 |
| 无省略号表示进行中 | ✅ 合规 | 所有文案不使用 `…` 表示 loading |
| Design Contract Gate | ✅ 合规 | 不改变主题/设计系统，复用现有 dialog 组件和节点样式 |
| AG-UI Protocol Contract Gate | N/A | 不涉及 AG-UI 协议 |
| Testing — 新功能必须有测试 | ⚠️ 需执行 | 后端: 新端点集成测试；前端: 弹框组件单元测试；E2E: Playwright 浏览器验证 |

## Project Structure

### Documentation (this feature)

```text
specs/002-ops-dag-viewer/
├── plan.md              # This file
├── spec.md              # Feature specification
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
│   └── published-dag-api.yaml
├── checklists/
│   └── requirements.md  # Spec quality checklist
└── tasks.md             # Phase 2 output (/speckit-tasks)
```

### Source Code (repository root)

```text
# Backend changes
backend/dataweave-api/src/main/java/com/dataweave/api/
└── interfaces/
    └── WorkflowController.java          # 新增 GET /api/workflows/{id}/published-dag

backend/dataweave-master/src/main/java/com/dataweave/master/
├── application/
│   └── WorkflowService.java             # 新增 readPublishedDag() 方法
└── domain/
    ├── WorkflowDefVersion.java          # 不变 — dagSnapshotJson 已存在
    ├── WorkflowDefVersionRepository.java # 新增按 workflowId + versionNo 查询
    └── WorkflowDagSnapshot.java         # 不变 — 反序列化 dagSnapshotJson

# Frontend changes
frontend/
├── lib/
│   └── types.ts                         # 不变 — DagView 已存在，可复用
├── components/
│   └── workspace/
│       ├── dag-viewer-dialog.tsx         # NEW: 只读 DAG 弹框组件
│       └── views/
│           └── ops/
│               ├── periodic-workflows-panel.tsx  # 修改: View DAG 按钮 → 打开弹框
│               └── manual-workflows-panel.tsx    # 修改: View DAG 按钮 → 打开弹框
└── messages/
    ├── zh-CN.json                        # 新增 dagViewer 相关 key
    └── en-US.json                        # 新增 dagViewer 相关 key
```

**Structure Decision**: 不新增模块，在现有结构内增量修改。前端核心新增文件 `dag-viewer-dialog.tsx` 作为独立可复用组件，被两个 panel 共享调用。后端只新增一个端点 + 一个 service 方法，数据层零改动。

## Complexity Tracking

> 无宪法违规，无需记录。
