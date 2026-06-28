# Implementation Plan: 文件契约 slug 唯一性与中文名健壮性修复（round-trip 保真）

**Branch**: `013-slug-roundtrip-fidelity` | **Date**: 2026-06-28 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/013-slug-roundtrip-fidelity/spec.md`

## Summary

修复文件契约 slug 派生对中文「类别-描述」命名的结构性塌陷：slug 保留连字符使多个不同实体坍缩成同一 slug（如 `-`/`-gmv`），导致拉取同名覆盖丢实体、差异比对因对称坍缩而失明、往返不可闭合。

技术路线（根因已锁死）：把 slug 的"退化"判定从 `isEmpty()` 改为"不含任何 `[a-z0-9]` 区分字符"，退化即回落确定性哈希；并在**已知兄弟集合**处加确定性唯一性守卫（同目录撞名按稳定 id 排序追加后缀）。导出（拉取落地文件名）与身份匹配（diff/push）**收敛到同一 slug+uniqueness 工具**（单一真相源），杜绝身份漂移。附带收口 CLI 按 code 拉取的 `items`/`content` 契约缝（已实现）。

## Technical Context

**Language/Version**: Java 25（后端 `dataweave-master`）；Go（`cli/` dw 二进制，US4 已实现部分）

**Primary Dependencies**: Spring Boot 4 / Spring Data JDBC（既有）；无新增第三方依赖（纯哈希用 JDK `MessageDigest`，CL-001 已定）

**Storage**: 不涉及 schema 变更；身份是派生（名字→slug），非持久列

**Testing**: JUnit5 + AssertJ（后端 slug 工具单测 + ProjectSyncService 往返集成测试，H2 profile）；Go `testing`（CLI resolve 回归）；E2E 闭环手动复跑（H2 后端 + 真 dw）

**Target Platform**: Linux server（后端）+ 开发者本机（dw CLI）

**Project Type**: 后端多模块（master）+ CLI；本特性聚焦 `filecontract` 命名/身份层

**Performance Goals**: N/A（命名派生是 O(实体数) 内存操作，非热路径）

**Constraints**: 身份 MUST 确定性、可往返、跨 pull 稳定、不依赖运行期随机/时钟；中文/data 术语命名一等支持；不改同步 API 端点契约与传输形态；最小扰动既有可读 ASCII 文件名

**Scale/Scope**: 单项目实体规模数十~数百；改动集中在 1 个 slug 工具 + 2 处调用收敛 + 测试

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 关系 | 判定 |
|---|---|---|
| **I. Files-First（确定性 on-disk 身份）** | 本特性正是修复"实体↔确定性 plain-text 文件路径"的派生缺陷 | ✅ 直接服务，强化 |
| **II. Server source of truth + Round-trip integrity** | constitution 明文"push 后 pull 到干净目录 MUST 语义等价"；当前对中文名不成立，本特性使其成立 | ✅ 直接服务，强化 |
| **III. Two-Legged Debugging** | 不涉及本地 runtime 真跑（已验证健康） | ✅ 不触及 |
| **IV. AI Lives in Local Agent** | 不涉及 | ✅ 不触及 |
| **V. Reuse the Kernel（复用不重写）** | 复用既有 `SlugRules`/同步管线，仅修正 slug 派生规则并收敛重复实现到单一真相源；不重写同步内核 | ✅ 符合 |

**Additional Constraints**：constitution「Round-trip integrity」与「Files-First 确定性」即本特性的硬验收锚点。红线包 `filecontract`/`ProjectSyncService` 修改已获项目所有者专项解禁（spec Assumptions 记录）。

**结论：Constitution Check 全过，无违背、无需 Complexity Tracking。**

## Project Structure

### Documentation (this feature)

```text
specs/013-slug-roundtrip-fidelity/
├── plan.md              # 本文件
├── research.md          # Phase 0：根因证据 + 修法决策 + 备选淘汰
├── data-model.md        # Phase 1：slug/身份模型（派生规则、退化、唯一性、稳定性）
├── quickstart.md        # Phase 1：E2E 闭环复跑剧本（验收锚点）
├── contracts/           # Phase 1：slug 命名契约 + pull-list 字段契约（非新增 REST 端点）
├── checklists/
│   └── requirements.md  # spec 质量单（已全过）
└── tasks.md             # Phase 2（speckit-tasks 产出，本命令不创建）
```

### Source Code (repository root)

```text
backend/dataweave-master/src/main/java/com/dataweave/master/
├── filecontract/naming/
│   └── SlugRules.java                 # 既有：校验 + 大小写碰撞守卫
│                                      # 【改】新增/收敛 slug 派生 + 退化判定 + 确定性唯一性守卫到此（单一真相源）
├── filecontract/mapping/
│   └── ProjectMapper.java             # 【改】deriveTaskSlug/deriveWorkflowSlug 收敛调用 naming 工具（删除本地正则副本）
└── application/
    └── ProjectSyncService.java        # 【改】slugify() 收敛调用 naming 工具；taskSlugs/workflowSlugs 装配
                                       #       + diff/push 身份匹配处统一经唯一性守卫（同目录兄弟集）

backend/dataweave-master/src/test/java/com/dataweave/master/
├── filecontract/naming/SlugRulesTest.java          # 【新】退化/撞名/纯标点/大小写/确定性 单测
└── application/ProjectSyncRoundtripTest.java        # 【新】中文碰撞夹具：pull 无损 / diff 忠实 / round-trip 稳定（H2）

cli/sync/
├── resolve.go            # 【已改】content→items（US4/FR-010）
└── sync_e2e_test.go      # 【已改】mock 改真契约 items（防自证错契约）
```

**Structure Decision**: 后端改动集中在 `dataweave-master` 的 `filecontract/naming`（slug 单一真相源）+ 两处调用收敛（`ProjectMapper` 导出侧、`ProjectSyncService` 身份侧）。CLI 侧 US4 已落。不新增模块、不新增 REST 端点、不改 schema。

## Complexity Tracking

> Constitution Check 无违背，本节不适用（留空）。
