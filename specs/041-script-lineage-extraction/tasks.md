# Tasks: 脚本任务血缘解析（Python/Shell 表与字段抽取 + 小模型通道）

**Input**: Design documents from `/specs/041-script-lineage-extraction/`

**Prerequisites**: plan.md, spec.md, research.md (D1-D12), data-model.md, contracts/lineage-script-api.md, quickstart.md

**Tests**: 包含。CLAUDE.md 硬规则（no test = not done）+ SC-001/002/006 百分比必须在标注语料库/评估集上断言。

**Organization**: 按用户故事分相；US1 单独构成可交付 MVP；US4（小模型）训练线与 US1-US3 并行，接入依赖 US2 基建，未达 SC-006 门槛不阻塞前三故事交付。

## Format: `[ID] [P?] [Story] Description`

## Path Conventions

- 后端 master：`backend/dataweave-master/src/{main,test}/java/com/dataweave/master/`
- 后端 api：`backend/dataweave-api/src/main/`
- 前端：`frontend/` · ML 子项目：`ml/lineage-extractor/`
- 后端每次编辑后 `./mvnw -q -pl <module> compile`；前端每次编辑后 `pnpm typecheck`（CLAUDE.md Post-Edit Verification，不单列任务）

---

## Phase 1: Setup（骨架）

**Purpose**: 可插拔抽取器契约先立（FR-010），后续所有故事在其上并行

- [x] T001 创建 `application/lineage/script/` 包与契约类型：`ScriptLineageExtractor.java`（接口：`supports(String taskType)` + `extract(ScriptSource)`）、`ScriptSource.java`（record：taskDefId/taskType/content/datasourceId/targetDatasourceId）、`ScriptExtraction.java`（record：reads/writes/columnEdges/hints/channel/modelVersion，channel 枚举 SCRIPT_SQL|SCRIPT_INFERRED|SCRIPT_MODEL；内嵌 record Hint{kind, line, snippet}），路径 `backend/dataweave-master/src/main/java/com/dataweave/master/application/lineage/script/`

---

## Phase 2: Foundational（阻塞全故事的公共底座）

**Purpose**: schema、图写入属性贯通、编排器核心——四个故事都踩在这上面

**⚠️ CRITICAL**: 本相完成前不得开始任何用户故事

- [x] T002 schema 变更：`backend/dataweave-api/src/main/resources/schema.sql` 新增 `lineage_edge_correction`（语义键唯一约束，column_key `NOT NULL DEFAULT ''` 保 H2 兼容）与 `lineage_unresolved_hint` 两表（DDL 按 data-model.md §1，含索引与 DROP 段对应行）；`schema_version` 0.6.2 → **0.7.0** 三处同步（版本行 INSERT、文件头注释、DB 行）
- [x] T003 [P] 图写入属性贯通：`backend/dataweave-master/src/main/java/com/dataweave/master/domain/lineage/Neo4jLineageStore.java` — `FLOWS_TO` 边补写 `{source, confidence}`（取两端读写边中较弱置信度），`DERIVES_FROM` 补 `source`，SCRIPT_MODEL 边透传 `modelVersion` 属性；确认 `IoEdge`/`ColumnEdge` source 字段能透传三个新值；同步修相关既有测试断言
- [x] T004 [P] 编排器核心：`application/lineage/script/ScriptLineageService.java` — 注入 `List<ScriptLineageExtractor>`，按 taskType 聚合各抽取器产物；冲突消解（同 (方向,表) 键优先序 **SCRIPT_SQL > SCRIPT_INFERRED > SCRIPT_MODEL**，FR-009/FR-012）；单任务 2s 时间预算（超时 → TIMEOUT hint、返回已得部分）；预留 correction 抑制钩子（US3 填充）。配套 `ScriptLineageServiceTest.java`（聚合/三级冲突消解/超时降级）于 `backend/dataweave-master/src/test/java/com/dataweave/master/application/lineage/script/`
- [x] T005 读侧 DTO 扩展：`backend/dataweave-master/src/main/java/com/dataweave/master/lineage/FlowEdgeView.java` 新增可选字段 `source`、`humanState`（本相恒 null）、`modelVersion`；`LineageQueryService` 读边回填——@JsonInclude(NON_NULL) 保证旧客户端无感

**Checkpoint**: `./mvnw -pl dataweave-master,dataweave-api compile` 零错误；schema 三处版本一致

---

## Phase 3: User Story 1 - 脚本内嵌 SQL 的血缘自动解析 (Priority: P1) 🎯 MVP

**Goal**: Python/Shell 脚本中字符串常量形式的内嵌 SQL 被挖出并复用 Calcite 链路，确定边（SCRIPT_SQL/CONFIRMED）入图

**Independent Test**: quickstart.md P1 — push 含 `INSERT INTO dw.orders SELECT id, amount FROM ods.orders` 的 Python 任务，图谱出现 `ods.orders→dw.orders` 实线边 + 字段边

### Tests for User Story 1（先写先红）

- [x] T006 [P] [US1] 语料库单测 `EmbeddedSqlExtractorTest.java`（`backend/dataweave-master/src/test/java/com/dataweave/master/application/lineage/script/`）：≥20 条带标注样例——Python 四种字符串形态各≥2（含变量中转 `sql = "…"; spark.sql(sql)`）、Shell 载体（hive -e / beeline -e / psql -c / mysql -e / spark-sql -e / heredoc）各≥1、多语句、CTE；负例：f-string/format 插值 SQL → 不出边出 DYNAMIC_SQL hint、`#` 注释与 print 内假 SQL → 不出确定边、纯计算脚本 → 零边零提示（AS-4）；断言表级正确率 ≥95%（SC-001 可执行化）
- [x] T007 [P] [US1] 集成测试 `ScriptLineagePushIT.java`（同测试包，h2 profile，`@SpringBootTest` + mock `LineageStore`，遵循 backend 测试隔离不变量）：push 含内嵌 SQL 的 PYTHON+SHELL 任务 → 断言 `recordTaskIo` 收到 SCRIPT_SQL 边且坐标走任务绑定数据源（FR-011）；抽取器抛异常 → push 仍成功（FR-005）

### Implementation for User Story 1

- [x] T008 [US1] `EmbeddedSqlExtractor.java` 实现（`application/lineage/script/`）：Python 字面量扫描（跳 `#` 注释，f-string/插值痕迹→hint）、Shell 载体命令与引号/heredoc 提取、SQL 首关键词嗅探 → 复用 `SqlTableExtractor.extract` 与 `SqlColumnLineageExtractor`（research D1）；T006 全绿
- [x] T009 [US1] push 路径接线：`ProjectSyncService.java` 5c-lineage 段（:848 起）type ∈ {PYTHON, SHELL}（SPARK 顺带）→ `ScriptLineageService.extract` → 产物并入同一 `lineageStore.recordTaskIo`；沿用既有 try-catch log.warn 降级模式，SQL 分支零改动；T007 全绿
- [x] T010 [US1] 上线路径接线：`TaskService.java` `recordLineage`（:475）同样并联脚本通道；tenant/project 硬编码 `1L,1L` 现状沿用不修（plan 风险条目）

**Checkpoint**: quickstart P1 手工通过——US1 可独立交付为 MVP

---

## Phase 4: User Story 2 - 非 SQL 代码写表场景的血缘推断 (Priority: P2)

**Goal**: 常见程序接口读写表被规则推断为 UNVERIFIED 虚线边（显式列清单出字段级边）；动态目标落未解析提示可查

**Independent Test**: quickstart.md P2 — push 用 `saveAsTable("dw.result")` 的任务，图谱现虚线写边；动态表名任务出 hint 不出边

### Tests for User Story 2（先写先红）

- [x] T011 [P] [US2] 语料库单测 `ApiPatternExtractorTest.java`（同测试包）：≥20 条样例——saveAsTable/insertInto/to_sql/read_sql/spark.read.table/sqoop import·export --table 正例；`df[["a","b"]].to_sql`、`sqoop --columns a,b` 字段级正例（断言字段级正确率 ≥90%）；变量/f-string 表名负例 → DYNAMIC_TABLE hint；断言表级召回 ≥80% 且错报 <10%（SC-002 可执行化）
- [x] T012 [P] [US2] hint 持久化测试 `LineageHintRepositoryTest.java`：replace-per-task 语义、按 (tenant,project,task) 查询、h2 兼容

### Implementation for User Story 2

- [x] T013 [US2] `ApiPatternExtractor.java` 实现（`application/lineage/script/`）：模式常量表（research D2 清单）识别读/写接口与 sqoop CLI 形态，表名字符串常量 → SCRIPT_INFERRED 边（UNVERIFIED），显式列清单 → 字段级边，动态表名 → Hint(DYNAMIC_TABLE)；T011 全绿
- [x] T014 [US2] hint 落库：`domain/lineage/LineageUnresolvedHint.java` 实体 + `LineageHintRepository.java`（Spring Data JDBC）；`ScriptLineageService` 抽取完成后按任务整体替换写入（含 TIMEOUT/PARSE_FAIL），try-catch 降级；T012 全绿
- [x] T015 [US2] hints 查询端点：`LineageGraphController.java`（`backend/dataweave-api/.../interfaces/`）新增 `GET /api/lineage/tasks/{taskDefId}/hints`（contracts §2，`ProjectScope` 隔离），错误走 `BizException`
- [x] T016 [P] [US2] 前端来源区分：`frontend/lib/lineage-api.ts` `FlowEdgeView` 类型补 `source`/`humanState`/`modelVersion` + `fetchTaskHints`；`frontend/components/workspace/views/lineage/lineage-flow.tsx` 图例补「推断（虚线）」（UNVERIFIED→虚线已有，:120）；i18n `lineageView` 命名空间双语同步

**Checkpoint**: quickstart P2 手工通过；US1 行为不回归

---

## Phase 5: User Story 3 - 血缘来源区分与人工修正 (Priority: P3)

**Goal**: 项目管理权限成员可在边详情面板确认/剔除/撤销推断边，语义键抑制在重复 push 中重放，动作过门禁留痕

**Independent Test**: quickstart.md P3 — 剔除→边消失→重 push 不复现→撤销→恢复；VIEWER 只读；agent_action 留痕

### Tests for User Story 3（先写先红）

- [x] T017 [P] [US3] 修正服务测试 `LineageCorrectionServiceTest.java`：语义键 UPSERT 幂等、REVOKE 删行、抑制查询命中
- [x] T018 [P] [US3] 抑制重放测试（`ScriptLineageServiceTest.java` 增补）：REMOVED 键过滤（SC-005 重现率 0）、CONFIRMED 升级、REVOKE 恢复
- [x] T019 [P] [US3] 门禁闭环集成测试 `LineageCorrectionGateIT.java`（h2）：POST corrections → L1 直通 → correction 落库 + `agent_action` 留痕断言；无项目管理权限 → 拒绝

### Implementation for User Story 3

- [x] T020 [US3] 修正域与服务：`domain/lineage/LineageEdgeCorrection.java` + `LineageCorrectionRepository.java`；`application/lineage/LineageCorrectionService.java`（UPSERT/REVOKE/查询/抑制键集 + 生效时同步修图，try-catch 降级）；T017 全绿
- [x] T021 [US3] 门禁接线：actionType `LINEAGE_EDGE_CONFIRM`/`LINEAGE_EDGE_REMOVE`/`LINEAGE_CORRECTION_REVOKE`；`backend/dataweave-api/src/main/resources/data.sql` `policy_rules` seed +3 行（均 L1，参照 :599）；`DefaultPlatformActionExecutor.java` switch 新增三 case → `LineageCorrectionService`（参照 projectPush 模式）
- [x] T022 [US3] 修正端点：`LineageGraphController.java` 新增 `POST /api/lineage/corrections`（→ `gatedActionService.submit`，contracts §1 幂等）与 `GET /api/lineage/tasks/{taskDefId}/corrections`；写端点 `ProjectScope.require` 项目管理权限；错误码 `lineage.edge_not_found`/`lineage.correction_conflict` 进后端 i18n；T019 全绿
- [x] T023 [US3] 抑制重放接入：`ScriptLineageService` correction 钩子（T004 预留）接抑制键集——REMOVED 过滤、CONFIRMED 升级；读侧 `LineageQueryService` 回填 `humanState`；T018 全绿
- [x] T024 [P] [US3] 前端边详情面板：新建 `frontend/components/workspace/views/lineage/edge-detail-panel.tsx`（任务/来源/置信度/modelVersion/人工状态 + hints 列表 + 确认/剔除/撤销按钮，`useProjectPermissions().can(...)` 门禁，样式参照 impact-panel.tsx）；`frontend/lib/lineage-api.ts` 补 `postCorrection`/`fetchTaskCorrections`
- [x] T025 [US3] 前端接线：`lineage-flow.tsx` 边加透明命中区 + onClick 选边（不破坏 impact 高亮，语义 token）；`lineage-view.tsx` 挂载面板，修正成功后局部刷新子图（禁 `…` 表示加载中）；i18n 双语同步
- [ ] T026 [US3] 浏览器实测（**deferred**：本机运行着 docker 分布式部署共享 PG/neo4j——本地起 0.7.0 后端会 DROP 共享库、replace-per-task 会按 taskDefId 误删真实图边；待隔离环境执行 quickstart P1-P4）（admin/admin JWT，深链 `/?open=lineage`）：quickstart P3 全流程 + VIEWER 只读，截图存项目根 `tmp/`

**Checkpoint**: US1-US3 全部独立可验

---

## Phase 6: User Story 4 - 小模型推断通道：训练、评估与接入 (Priority: P4)

**Goal**: 合成数据管线 → 12G GPU QLoRA 微调 Qwen2.5-Coder-1.5B → held-out 过 SC-006 门槛 → HF 发布 → 推理 sidecar → ModelExtractor 接入（SCRIPT_MODEL 边），降级零阻断

**Independent Test**: quickstart.md P4 — 评估退出码 0；规则不覆盖形态出「模型推断」虚线边；停 sidecar 重 push 零阻断自动降级

**依赖说明**: T027-T033（数据/训练/评估/发布/sidecar）与 US1-US3 完全并行（纯 ml/ 目录）；T034-T036（平台接入）依赖 Phase 2 + US2 的 T013/T014

### 数据与训练线（ml/ 目录，与后端并行）

- [x] T027 [US4] HF 认证与数据源核实：经 huggingface MCP 完成用户账号认证；核实 `gretelai/synthetic_text_to_sql`（Apache-2.0）与 `b-mc2/sql-create-context`（cc-by-4.0）可用性与许可（research D10；不可用则按同许可标准选替代并记录进 research）；建私有 repo 占位 `<user>/weft-lineage-extractor-1.5b`、`<user>/weft-script-lineage-synth`
- [x] T028 [US4] 合成数据管线：`ml/lineage-extractor/data/synth_pipeline.py` + `data/templates/`（≥30 种 Python/Shell 写表形态模板 + 负例模板：注释假 SQL/f-string 动态表名/无关代码；含自定义封装函数形态若干**仅入 heldout** 做形态隔离）——SQL/表名池来自 T027 数据集，标签注入自动产生；输出 train ~10k / heldout ≥200 JSONL（data-model 契约，按 template_id 分层采样 + split_group 隔离）；`requirements.txt` 锁版本 + 固定 seed
- [x] T029 [US4] 训练：`ml/lineage-extractor/train/sft_qlora.py`——Qwen2.5-Coder-1.5B-Instruct + QLoRA（r=16，bf16 base 4bit 量化，D9 配方），受约束 JSON 输出格式；12G 显存内完成（峰值 ~8G），checkpoint 落 `ml/lineage-extractor/out/`（git-ignore）
- [x] T030 [US4] 评估门槛闸：`ml/lineage-extractor/eval/evaluate.py`——heldout 集上算 SC-006 三指标（表级 precision ≥85% / 规则未覆盖子集召回 ≥60% / 幻觉率 ≤2%）+ 字段级正确率参考值；输出报告 markdown + **退出码 0/1 表达是否过闸**（FR-014：不过闸则 T034-T036 不执行，记录差距，US1-US3 照常交付）
- [x] T031 [US4] 迭代预算：若 T030 不过闸，允许 ≤2 轮数据/超参迭代（每轮记录差距与改动）；仍不过 → 触发 research D9 备选路线评估记录（encoder token-classification），本期以规则通道交付收尾
- [ ] T032 [US4] HF 发布（**blocked**：当前 HF token 只读 403——换 write 权限 token 后执行 `python3 publish.py`；评估已过闸、工件在 ml/lineage-extractor/out/run1/merged）：`ml/lineage-extractor/publish.py`——模型（LoRA 合并权重）+ 数据集 + 评估报告卡发布到 T027 私有 repo，版本标 `@v1`（FR-015）
- [x] T033 [US4] 推理 sidecar：`ml/lineage-extractor/serve/app.py`（FastAPI）——`POST /extract`（contracts §5：温度 0 确定性解码、同输入同输出）+ `GET /health`；README 记 GPU 常驻与 CPU 量化两种启动方式

### 平台接入线（依赖 Phase 2 + T013/T014 + T030 过闸）

- [x] T034 [P] [US4] `ModelExtractorTest.java`（先写先红，同后端测试包）：mock sidecar——正常输出转 SCRIPT_MODEL/UNVERIFIED 边（带 modelVersion）；幻觉输出（表名不在 content 中）被校验拒收；超时/5xx/endpoint 未配置 → supports=false 或空产物，零异常外抛
- [x] T035 [US4] `ModelExtractor.java` 实现（`application/lineage/script/`）：WebClient（自建 @Bean，SB4 无自动配置）调 sidecar，配置键 `lineage.model.endpoint`（未设→旁路）/`lineage.model.timeout`（默认 2s）；启动+周期探活失败→ `supports()` false；输出双重校验（schema + 表名 content 内可定位）；T034 全绿
- [x] T036 [US4] 端到端验证（sidecar 冒烟 ✅：spark.table/impala-shell 等规则未覆盖形态含字段级抽取正确、health/降级路径经 ModelExtractorTest 覆盖；**push→图 E2E 与前端展示待隔离环境**，原因见 T026 注记）：quickstart P4 全流程——规则不覆盖形态入图（边详情显示来源「模型推断」+modelVersion，前端 i18n 补 key 双语）、停 sidecar 降级零阻断（SC-007）、`ScriptLineageServiceTest` 增补 MODEL 优先序被 RULE/SQL 压制用例

**Checkpoint**: 四故事全部可验；模型未过闸时 US4 以 T030 差距报告收尾、平台侧 ModelExtractor 保持旁路

---

## Phase 7: Polish & 回归红线

- [x] T037 后端全量回归（WSL2 setsid 脱离 + build-cache 关闭防假绿）：`setsid bash -c 'cd backend && ./mvnw -pl dataweave-master,dataweave-api test -Dmaven.build.cache.enabled=false >build.log 2>&1; echo $? >build.exit' </dev/null >/dev/null 2>&1 & disown`——既有 SQL 血缘测试全绿（SC-003 零行为回归），只认 `Tests run: N>0`
- [x] T038 [P] 前端回归：`cd frontend && pnpm typecheck && pnpm test`；i18n 双语 key 集一致
- [ ] T039 [P] quickstart.md 全场景走查（P1/P2/P3/P4 + 回归红线：语法错误脚本 push 成功出 PARSE_FAIL hint、schema_version 三处一致 0.7.0）
- [ ] T040 push 耗时抽样验证（测试环境数据已记 perf-note.md：规则通道毫秒级远低于 20% 上限；**生产形态抽样待隔离环境**） SC-003（含脚本任务与模型通道开启两种形态，增幅 ≤20%），结果记入 spec 目录 `perf-note.md`

---

## Dependencies & Execution Order

### Phase Dependencies

- Phase 1 → Phase 2 → {Phase 3, 4, 5, 6 按依赖并行} → Phase 7
- **US1**: 仅依赖 Foundational；T008 依赖 T001；T009/T010 依赖 T004+T008
- **US2**: 依赖 Foundational（T002 hint 表、T004 编排器）；抽取器本体可与 US1 并行
- **US3**: 依赖 T002（correction 表）+ T004（抑制钩子）；面板展示依赖 US2 有推断边才有意义
- **US4**: 训练线 T027-T033 零后端依赖、可最早启动（甚至与 Phase 1-2 并行）；接入线 T034-T036 依赖 Phase 2 + T013/T014 + **T030 过闸**
- 故事内：测试(红) → 实现(绿) → 接线 → 集成

### Parallel Opportunities

- Phase 2 内：T003 ∥ T004
- 训练线 T027-T033 与整个后端开发并行（不同目录零冲突）——**建议最早启动 T027/T028**（训练与评估有日历时间）
- 红测试跨故事同开：T006/T011/T017/T034
- T016 ∥ T015；T024 ∥ T021-T023；Phase 7：T038 ∥ T039

### Parallel Example: 全局最优开局

```bash
# 开局三线并行：
Task: "T001 契约骨架"（后端线）
Task: "T027 HF 认证与数据源核实"（ML 线，最长日历路径先行）
Task: "T028 合成数据管线"（ML 线，T027 后）
```

---

## Implementation Strategy

**MVP = Phase 1 + 2 + US1**（内嵌 SQL 确定边）——零前端改动即消除脚本血缘盲区主体。随后增量：US2（推断+提示）→ US3（修正闭环）→ US4 接入（其训练线早已并行推进）。**US4 风险隔离**：SC-006 门槛闸（T030 退出码）是硬闸——不过闸不接入，US1-US3 交付不受影响；迭代预算封顶 2 轮（T031）防训练线无限吞噬工期。每故事完成即 quickstart 对应段验证 + checkpoint 提交。

## Notes

- 总计 40 任务：Setup 1 · Foundational 4 · US1 5 · US2 6 · US3 10 · US4 10 · Polish 4
- 所有 [P] 任务 = 不同文件且无未完成依赖
- `ml/lineage-extractor/out/`、模型权重、数据集产物 git-ignore；工件真相源在 HF 私有 repo
- 提交粒度：每任务或同故事逻辑组一提交；worktree 内分支 `041-script-lineage-extraction`
