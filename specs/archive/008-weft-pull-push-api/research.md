# Research — Weft 子特性 C(pull/push API)

Phase 0 设计决策。每条:Decision / Rationale / Alternatives。所有决策锚定已勘查的服务端事实(见各条引用)。

---

## D1 — push 如何"生成版本快照但不晋级 ONLINE"(头号决策)

**Decision**: 从 `TaskService.publish()` 与 `WorkflowService.publish()` 各抽出一个**状态中立的建快照内核**:
- `TaskService.writeTaskVersionSnapshot(TaskDef task, Long publishedBy, String remark) → Integer newVersionNo`:建 `TaskDefVersion` 行(复制 name/type/content/datasourceId/targetDatasourceId/paramsJson/timeout/retry/priority/description),`versionNo = currentVersionNo+1`,保存并回写 `task.currentVersionNo`;**不**改 `task.status`、**不**做"无草稿变更"报错。
- `WorkflowService.writeWorkflowVersionSnapshot(Long workflowId, String remark) → Integer`:复用现有 `buildSnapshotJson(id, nodes)` 建 `WorkflowDefVersion`(含 `dagSnapshotJson` 钉 `taskVersionNo`),`versionNo+1`,保存并回写 `currentVersionNo`;**不**晋级 ONLINE、**不**做"被引用任务须 ONLINE"闸门、**不**做"空 DAG"报错(空 DAG 在 push 校验阶段另行处理)。

`publish()` 重构为 `writeXxxVersionSnapshot(...) + 晋级 ONLINE + 引用完整性闸门 + 无变更守卫`,**对外行为零回归**(现有 publish 测试须仍绿)。push 只调建快照内核。

push 后实体状态:新建实体 `status=DRAFT`;既有实体保持原 `status` 并置 `hasDraftChange=1`(与现有"编辑即草稿"路径一致)。`currentVersionNo` 前进到本次 push 的快照号——**可调度性由 `status` 决定**(调度器只跑 ONLINE),故 currentVersionNo 前进对非 ONLINE 实体不产生运行态副作用;漂移计算(`computeDrift`)只对 ONLINE workflow 生效,不受影响。

**Rationale**: ① 满足 constitution II + FR-009"push 生成不可变版本快照"(治理/溯源/round-trip 真相);② 满足 Q1"push 不自动上线";③ Reuse-the-Kernel(Principle V)——复用而非重写,抽取后两处共用一条建快照逻辑,杜绝漂移;④ 上线仍是显式动作(`publish()` 不变),解耦提交与生产化。

**Alternatives**:
- *push 直接调 `publish()`*:被拒——会晋级 ONLINE 并被"被引用任务须 ONLINE"闸门阻塞,违反 Q1。
- *push 只存草稿、完全不建版本行*:被拒——违反 FR-009 与 constitution II"push generates a new version snapshot";丢失每次 push 的不可变溯源。
- *push 建版本行但不动 currentVersionNo*:被拒——currentVersionNo 与最新快照脱节,后续 publish/diff 语义混乱;且无运行态收益(status 已挡住调度)。

**实现护栏(评审重点)**:抽取后必须跑通现有 `TaskService`/`WorkflowService` 的 publish 相关测试,证明 publish 行为不回归。

---

## D2 — push 落库:B 的合成 id ↔ 真实 DB 行的身份匹配算法

**Decision**: `FileContract.deserialize(bundle)` 产出的 `ProjectImport` 内实体带**确定性合成 id**(B 按排序路径分配,见 B 实现),仅用于 bundle 内部连边。C 落库时**忽略合成 id**,按**身份键**匹配该 project 下的真实 DB 行:

| 实体 | 身份键(project 作用域内) | 数据源 |
|------|---------------------------|--------|
| Catalog | `path`(物化目录路径,如 `orders`) | `CatalogNodeRepository.findByProjectIdAndDeleted(pid,0)` |
| Task | `slug`/`path`(文件名去扩展,B 的 `taskSlugs`) | `TaskDefRepository.findByProjectId(pid)`,按 (catalogPath, slug) 或 name 匹配 |
| Workflow | `slug`(B 的 `workflowSlugs`) | `WorkflowDefRepository.findByProjectId(pid)` |
| Tag | `name`(项目内唯一) | `TagRepository.findByProjectIdOrderByNameAsc(pid)` |
| Datasource(只读引用) | `name`(项目内唯一) | `DatasourceRepository.findByProjectId(pid)`,建 name→id 映射 |

匹配三态:**(a)** 本地有 + 服务器有 → **update**(回填真实 id,更新字段,`hasDraftChange=1`);**(b)** 本地有 + 服务器无 → **insert**(新 id,`status=DRAFT`);**(c)** 本地无 + 服务器有 → **delete**(经 D6 引用守卫,软删 `deleted=1`)。
workflow 的 node/edge 以 workflow 为父**整体重建**(先按 workflowId 删旧 node/edge,再插新),node.taskId 经 task 身份匹配解析,edge from/to 经 node.nodeKey 解析——避免 node/edge 级增量匹配的复杂度。

**Rationale**: 身份=path/slug 是 B 的既定原则(实体身份不依赖服务端数字 id);name/path 在 project 内已唯一(`existsByProjectIdAndName*` 等约束佐证)。node/edge 整体重建因其依附 workflow、量小、且 nodeKey 是 B 内稳定标识,重建比增量匹配更简单可靠。

**Alternatives**: *信任 B 的合成 id 直接当 DB id*:被拒——合成 id 是 bundle 内部产物,与 DB 真实 id 无关,会撞号/错配。*node/edge 增量 diff*:被拒——复杂度高、收益低(它们无独立版本治理)。

---

## D3 — 数据源逻辑名解析(承 clarify Q5)

**Decision**: 文件中 `datasource` / `targetDatasource` 的值 = `Datasource.name`(项目内唯一)。push 用 `DatasourceRepository.findByProjectId(pid)`(或补 `findByProjectIdAndName`)构建 `name → datasourceId` 映射,解析 `TaskDef.datasourceId/targetDatasourceId`。名在项目内不存在 → 可定位错误拒绝(FR-007),不创建数据源。pull 反向:`datasourceId → name`,写入 `ProjectExport.taskDatasourceCodes/taskTargetDatasourceCodes`(B 已支持这两个 map)。

**Rationale**: 零 schema 变更;name 人类可读且唯一;契合 B 的契约(B golden 用 `warehouse_main` 形态)。`Datasource` 实体无 `code` 字段(已勘查),name 即逻辑名。

**Alternatives**: 新增 `Datasource.code` 字段 / 文件写数字 id —— 均被 Q5 否决。

**护栏**: name 可能含中文/空格,而文件中 datasource 值是行内标量(非文件名),B 的 YAML 标量可承载任意 UTF-8,故无可移植性约束问题;仅需 push 时精确字符串匹配。

---

## D4 — 乐观并发基线令牌(承 clarify Q2)

**Decision**: 基线令牌 = 服务器对该 project 当前定义计算的**不透明修订摘要**(opaque revision token):对该 project 下全部 task/workflow/catalog/tag 的 `(id, version|updatedAt)` 按稳定顺序拼接后取哈希(如 SHA-256 前 16 字节 hex)。pull 在 `PullResult.baseline` 返回;push 在 `PushCommand.baseline` 携带。push 落库前服务器**重算当前修订**,与携带值比对:不等 → `BizException("project.sync.stale")` 拒绝(除非 `force=true`)。

**Rationale**: 零新表、零新列;摘要随任意定义变更而变(insert/update/delete 都改 version/updatedAt 或集合成员);`force` 提供逃生门。比"逐字段比对"轻,比"加 project.sync_revision 列"少侵入。

**Alternatives**: *新增 `projects.sync_revision` 单调列*:被拒——schema 变更 + 需在所有写路径维护,过度。*bundle 内容哈希做基线*:被拒——服务器合法的规范化/排序会让等价内容哈希不同,误报陈旧。

**护栏**: 令牌计算须**稳定排序**(按 id),否则误报;评审验证空项目(无实体)也产出确定令牌。

---

## D5 — 事务性全有或全无(FR-006)

**Decision**: `ProjectSyncService.push(...)` 标 `@Transactional`。顺序:**先全量校验**(B 反序列化 warnings 视为致命、datasource 名解析、删除引用守卫、基线校验、完整性校验)→ 全部通过后才进入落库(upsert/软删 + 写快照)。任一校验失败**早返回**(未发生任何写);任一落库异常 → 事务回滚。push 是写路径,落 `agent_action` 审计。

**Rationale**: 强一致优于最终一致(用户明确要求);Spring 声明式事务是既有约定;"校验前置、写在后"保证拒绝路径零副作用。

**Alternatives**: *逐实体提交*:被拒——半成品落库,违反 FR-006。

**护栏**: 校验阶段绝不可有写副作用(评审检查 `writeTaskVersionSnapshot` 不被校验阶段误调)。

---

## D6 — 删除的在线引用守卫(承 clarify Q3)

**Decision**: 对三态中的 (c)删除候选,若是 **task**:查 `WorkflowNodeRepository.findByTaskIdAndDeleted(taskId,0)`,对每个引用 node 取其 workflow,若存在 `status=ONLINE` 的 workflow 引用 → 整单 push 以 `BizException("project.sync.delete_referenced", taskName, workflowName)` 拒绝。若是 **workflow**:直接软删(workflow 不被别的 workflow 引用)。删除=软删 `deleted=1`,历史 `*_version` 行保留(不物理清除)。

**Rationale**: 兑现"目录即真相→缺失即删"的同时不破坏运行态(constitution IV"不得损伤运行态");软删 + 保留快照符合现有软治理。

**Alternatives**: *无条件硬删* / *软删但允许被引用*:均被 Q3 否决。

---

## D7 — pull 零凭据 + 空项目(FR-003 / US1-4)

**Decision**: pull 装配 `ProjectExport` 时,task 的数据源仅以 `name` 进入 `taskDatasourceCodes` 映射;**绝不**读取/写入 `Datasource` 的 host/port/username/passwordEnc/jdbcUrl/propsJson。空项目(无 task/workflow)仍装配 project + catalog 骨架,`FileContract.serialize` 对空集合产出最小文件集(project.yaml + tags.yaml + 空类目标记),不报错。

**Rationale**: FR-003/SC-005 硬要求;B 的序列化已只消费 ProjectExport 给定字段,C 只要不把连接字段塞进去即可。空项目复用 B 对空类目/空集合的既定行为。

**Alternatives**: 无(硬需求)。

---

## 复用面索引(实现锚点)

| 复用点 | 位置 |
|--------|------|
| 文件序列化/反序列化 | `filecontract/FileContract.java` `serialize/deserialize` |
| 导出/导入聚合 | `filecontract/ProjectExport.java`(12 参)`ProjectImport.java`(`toExport()` + getters) |
| 任务快照内核 | `application/TaskService.java:280 publish()`(D1 抽取) |
| 任务流快照内核 | `application/WorkflowService.java:674 publish()` + `buildSnapshotJson:736`(D1 抽取) |
| 8 聚合查询 | `domain/*Repository.findByProjectId*` |
| 数据源解析 | `domain/DatasourceRepository.findByProjectId` |
| 隔离上下文 | `dataweave-api/infrastructure/TenantContext.java` |
| REST 信封/错误 | `dataweave-api/infrastructure/ApiResponse.java` · `master/i18n/BizException` |
| 删除引用查询 | `domain/WorkflowNodeRepository.findByTaskIdAndDeleted` |
