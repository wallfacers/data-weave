# Implementation Plan: 文件化定义契约(Weft File Contract)

**Branch**: `007-weft-file-contract`(单目录 main 直推,见 Constitution Check 偏差记录) | **Date**: 2026-06-27 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/007-weft-file-contract/spec.md`

## Summary

为 project / catalog / task / workflow 四类实体定义**确定性纯文本磁盘表示**与**双向 round-trip 契约**,作为 Weft「任务即代码」的地基(解锁 C 的 pull/push)。技术上:在 `dataweave-master` 新建纯转换库 `com.dataweave.master.filecontract`,以 **SnakeYAML 2.5(已在 classpath)+ 显式 DTO↔有序 Map 映射层**实现序列化/反序列化,字段级掌控键序、null 语义、精确报错、前向兼容;脚本体作为独立原生语言文件与元数据物理分离;实体身份 = 文件路径/文件名(clean-slate 不依赖服务端数字 id);只表达源定义(版本快照归服务器)。交付物 = 格式契约(JSON Schema)+ Java 参考实现 + round-trip/确定性/错误/金文件测试。

## Technical Context

**Language/Version**: Java 25(本机 JDK 25 symlink swap)

**Primary Dependencies**: SnakeYAML 2.5(Spring Boot 4.0 传递依赖,已在 classpath);**不**引入 jackson-dataformat-yaml(Jackson 2.20 变体,与主库 Jackson 3 混用风险);B 自身不用 Jackson 注解

**Storage**: 无(B 是纯内存转换;`ProjectFileBundle` = `Map<相对路径,String>`;真实 IO 归 C/D)

**Testing**: JUnit 5 + AssertJ;纯单元测试(无 `@SpringBootTest`、无 DB);金文件置于 `dataweave-master/src/test/resources/filecontract/`

**Target Platform**: 后端 JVM(`dataweave-master` 模块内库);格式面向本地文件系统 + git,跨 Linux/macOS/Windows

**Project Type**: 既有 Maven 多模块后端内的**新增库包**(非新模块、非前端)

**Performance Goals**: 无硬指标(单项目数百任务量级,序列化为内存操作,毫秒级);确定性优先于吞吐

**Constraints**: 确定性逐字节稳定(FR-010)、双向 round-trip(FR-011)、diff 局部性(FR-013)、零真实 IO 依赖(可纯单测)、不依赖服务端自增 id(FR-007)

**Scale/Scope**: 4 类实体 + 标签 + 项目根;约 5 类文档 DTO、~30 字段;一个项目典型 10²~10³ 任务

## Constitution Check

*GATE: 通过方可进入 Phase 0;Phase 1 设计后复检。*

| 原则 | 结论 | 依据 |
|---|---|---|
| I. Files-First | ✅ 直接落地 | 元数据/脚本物理分离(FR-001)、目录树=类目树(FR-003)、纯文本可 diff/review(FR-010/013) |
| II. Server is Source of Truth | ✅ 不越界 | 只序列化源/草稿定义,版本快照与租户/项目隔离归服务器(FR-014、research D5) |
| III. Two-Legged Debugging | ✅ 不冲突 | B 不碰执行器;为 D 的本地 runtime 提供可读文件基底 |
| IV. AI Lives in Local Agent | ✅ 不冲突 | B 不引入任何服务端 AI;格式对本地 agent 友好 |
| V. Reuse the Kernel | ✅ 复用 | 复用既有 TaskDef/WorkflowDef/CatalogNode/Tag 领域语义、nodeKey/strength 概念,只定义文件投影(FR-017) |
| No Legacy Migration(硬规范) | ✅ 遵守 | 不建迁移路径;clean-slate 格式,不依赖既有数字 id |
| Round-trip integrity(硬规范) | ✅ 核心交付 | FR-011 + data-model §7 不变量 R1-R6 + 测试 |

**记录的偏差(Deviation)**:宪法「Development Workflow」建议转型在**独立 git worktree** 推进以免污染近期合并工作。**本特性在单目录 main 直推,不用 worktree。**
- **理由**:A 已合并入 main,Weft 转型已成为仓库**唯一在飞特性**(无兄弟特性争 SDD 指针、无并行改文件冲突),worktree 隔离的原初理由(防污染 distributed-cron / ops-dag-viewer 的并行工作)已不成立——main 本身即 Weft 线。用户明确要求 AI 时代单目录操作。该偏差经用户批准,符合宪法「deviations MUST be recorded with explicit written rationale and approved」。

**门槛结论**:无未justified违规,Complexity Tracking 留空。

## Project Structure

### Documentation (this feature)

```text
specs/007-weft-file-contract/
├── plan.md              # 本文件
├── spec.md              # 已含 Clarifications(4 项裁定)
├── research.md          # Phase 0:工具链选型 D1-D10
├── data-model.md        # Phase 1:文件形状 + 字段↔领域映射 + 往返不变量
├── quickstart.md        # Phase 1:完整示例树 + 测试跑法 + API 形状
├── contracts/           # Phase 1:5 类文档 JSON Schema
│   ├── README.md
│   ├── project.schema.json
│   ├── tags.schema.json
│   ├── folder.schema.json
│   ├── task.schema.json
│   └── workflow.schema.json
└── checklists/requirements.md
```

### Source Code (repository root)

```text
backend/dataweave-master/src/main/java/com/dataweave/master/filecontract/
├── FileContract.java              # facade:serialize(ProjectExport)/deserialize(ProjectFileBundle)
├── ProjectFileBundle.java         # 内存文件树 Map<相对路径,String(UTF-8)>
├── ProjectExport.java             # 出域聚合:project + catalogs + tasks + workflows + tags(领域对象)
├── ProjectImport.java             # 入域聚合 + 校验结果
├── dto/                           # 文件形状 record
│   ├── ProjectDoc.java
│   ├── TagsDoc.java
│   ├── FolderDoc.java
│   ├── TaskDoc.java
│   └── WorkflowDoc.java           # 含 Node/Edge 子 record
├── yaml/
│   └── DeterministicYaml.java     # SnakeYAML dump/load 封装(固定 DumperOptions + 有序 Map 助手)
├── mapping/
│   ├── ProjectMapper.java
│   ├── CatalogMapper.java
│   ├── TaskMapper.java            # 含 type→扩展名、params↔规范 JSON、datasourceId↔code
│   ├── WorkflowMapper.java        # 含 node/edge、task 相对路径、strength 默认
│   └── TagMapper.java             # EntityTag 内联 ↔ tags.yaml 调色板
├── naming/
│   └── SlugRules.java             # FR-007a 可移植字符集 + 大小写冲突校验
└── error/
    └── FileContractException.java # file + locus + message(FR-015)

backend/dataweave-master/src/test/java/com/dataweave/master/filecontract/
├── RoundTripTest.java             # R2/R3 双向
├── DeterminismTest.java           # R1/R4
├── GoldenFileTest.java            # 金文件逐字节
├── DiffLocalityTest.java          # R5 / SC-004
├── ErrorHandlingTest.java         # FR-015 各类错误
└── NamingConstraintTest.java      # FR-007a

backend/dataweave-master/src/test/resources/filecontract/
└── sample-project/                # 金文件夹(quickstart 的 analytics 树)
```

**Structure Decision**:落在既有 `dataweave-master` 模块新增包 `com.dataweave.master.filecontract`,纯转换库(无 IO、无 Spring 上下文依赖),便于纯单测与被 C 直接调用。不新建 Maven 模块、不动前端。SnakeYAML 已在 classpath,`dataweave-master/pom.xml` 仅需显式声明 `org.yaml:snakeyaml`(把传递依赖提为直接依赖,避免隐式)。

## Phase 0 Outcome

见 [research.md](./research.md):D1 SnakeYAML 选型 · D2 显式映射 · D3 确定性规则 · D4 null/零语义 · D5 字段范围 · D6 params 表示 · D7 脚本扩展名 · D8 前向兼容 · D9 分层 · D10 既有技术债(不在 B)。无遗留 NEEDS CLARIFICATION。

## Phase 1 Outcome

- [data-model.md](./data-model.md):目录布局 + 5 类文档字段表 + 领域映射 + 校验规则(FR-015)+ 往返不变量 R1-R6。
- [contracts/](./contracts/):5 类文档 JSON Schema(Java 与未来 Go CLI 共同遵循)。
- [quickstart.md](./quickstart.md):完整示例树 + 测试跑法 + C/D 接线 API 形状。

**Post-Design Constitution 复检**:设计未引入新违规;分层(D9)保持 domain ← application 依赖方向(filecontract 属 application 级,依赖 domain 模型,不被 domain 依赖);无新模块、无服务端 AI、无执行器分叉。✅

## Complexity Tracking

> 无 Constitution 违规需要justify,留空。

## Next Steps

- `/speckit-tasks` 生成任务分解(建议按 US 优先级:US1/US2 P1 → US3 P2 → US4 P3;先 DTO+DeterministicYaml+mapper,再 round-trip 测试,后错误/命名/金文件)。
- 实现期遵守:每改一处 `./mvnw -q -pl dataweave-master compile`;测试无 Spring 上下文;无测试不算完成。
