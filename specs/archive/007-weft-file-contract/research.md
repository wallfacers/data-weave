# Phase 0 Research: 文件化定义契约(007-weft-file-contract)

调研目标:为 B 的「确定性 YAML 序列化 + round-trip 契约」在本仓库(Spring Boot 4 / Jackson 3 / Java 25)选定工具链与设计规则,消解所有 NEEDS CLARIFICATION。

## D1. YAML 引擎选型

**Decision**:直接使用 **SnakeYAML 2.5**(已在 classpath,Spring Boot 4.0 传递依赖,用于 `application.yml`),配 `DumperOptions` 固定输出风格;**不**使用 Jackson 做 YAML。

**Rationale**:
- 本仓库 YAML 能力来自 `org.yaml:snakeyaml:2.5`(确认在 classpath)。`jackson-dataformat-yaml` 仅以 **Jackson 2.20.1** 变体作为传递依赖存在,而主 `ObjectMapper` 是 **Jackson 3.0.2(`tools.jackson`)**——用 Jackson 2 的 YAML + Jackson 3 注解会注解识别混乱(现有 `WorkflowDagSnapshot` 已误用 `com.fasterxml.jackson.annotation.*`,是反面教材)。
- B 的字段集很小(5 类文档、约 30 字段),且对**确定性、null 省略、精确错误、前向兼容**有硬要求(FR-010/012/015/016)。这些都需要对序列化逐字段掌控——把结构构造成**有序 `Map`/`List`(LinkedHashMap 按固定顺序插入)**再用 SnakeYAML `dump`,比任何反射式映射都可控。
- 零新依赖、不引入版本冲突。

**Alternatives considered**:
- `jackson-dataformat-yaml`(Jackson 2.20):在 classpath 但与主库 Jackson 3 混用,且对 null 省略/键序/块标量的控制弱于手控 Map;否决。
- `tools.jackson.dataformat:jackson-dataformat-yaml:3.x`:Jackson 3 的 YAML 模块是否已发布未确认,不赌不可控依赖;否决。
- 手写 YAML emitter:重复造轮子,易错;否决(SnakeYAML 的 emitter 在相同输入+选项下本就确定)。

**DumperOptions(固定)**:block style(`DEFAULT_FLOW_STYLE=BLOCK`)、indent=2、不输出 `---` 文档头、行尾 LF、禁用 anchor/alias(`setAllowReadOnlyProperties` 无关;通过只 dump 纯 Map/List/标量天然无 anchor)、`splitLines=false`(不按列宽折行,保证字符串稳定)、末尾单个换行。

## D2. 映射策略:显式 DTO ↔ 有序 Map(不反射绑定)

**Decision**:为每类文档定义**文件形状 DTO(record)**;序列化走 `DTO → 有序 LinkedHashMap → SnakeYAML.dump`;反序列化走 `SnakeYAML.load → Map → 手工读字段填 DTO`,字段缺失/类型错由 mapper 显式抛带定位的错误。DTO ↔ 领域对象由独立 mapper 完成。

**Rationale**:手工读 Map 让我们能①精确报「哪个文件、哪个字段」错(FR-015);②自定义未知字段处理(FR-016 忽略而非崩);③精确区分 null/缺失 vs 0/空串(FR-012);④固定键的输出顺序(FR-010)。反射式绑定(SnakeYAML `Constructor(Class)` 或 Jackson)会把这些控制权交给库,难满足契约。

**Alternatives**:SnakeYAML `Constructor(TargetClass)` 自动绑定——键序与 null 处理不可控、错误信息差;否决。

## D3. 确定性规则(FR-010/013)

**Decision**:
- **键顺序**:每类文档的字段按**契约文档固定的声明顺序**输出(LinkedHashMap 插入序),非字母序(更贴近人类阅读)。
- **集合排序**:tasks/workflows 落文件由文件名决定无需排;文档内集合按稳定键排序——`tags` 按 name 升序;workflow `nodes` 按 `key` 升序;`edges` 按 `(from, to, strength)` 升序;`params` 键按 name 升序。
- **无易变值**:不写自增 id、绝对时间戳、随机数、服务端派生字段(见 D5)。
- **行尾/缩进**:LF、indent 2、UTF-8、文件末尾恰好一个换行。

## D4. null/缺失 vs 0/空(FR-012)

**Decision**:可空字段为 null/未设置时**整键省略**;值为 0 / 空串 / false 时**显式写出**。反序列化:键缺失 → null;键存在值为 0 → 0。布尔 `frozen`、整数 `sortOrder/priority/timeoutSec/retryMax` 等遵循此规则。

## D5. 哪些字段进文件(源定义)vs 服务器治理(FR-014)

**Decision**:文件**只含源/可编辑定义**。下列服务端治理字段**绝不进文件**,由服务器在 push/ingest 时分配或维护:`id`、`tenantId`、`projectId`(项目身份用 `code`)、`status`、`currentVersionNo`、`hasDraftChange`、`version`(乐观锁)、`createdAt/updatedAt/createdBy/updatedBy`、`catalogNodeId`(由目录推导)、`datasourceId`(用逻辑 code 替代)、`lastFireTime/nextTriggerTime`、`ownerId`(治理归属,push 时按上下文定)、物化 `path`。已发布版本快照(TaskDefVersion/WorkflowDefVersion)整体不进文件。

**Rationale**:对齐宪法原则 II + FR-007(clean-slate 不依赖数字 id)+ FR-008(类目唯一真相源=目录)。

## D6. 参数(paramsJson)的文件表示

**Decision**:领域里 `paramsJson` 是一段 JSON 字符串;文件里**展开为结构化 YAML map**(键按字典序),读回时**重新序列化为规范 JSON 字符串**(键有序、紧凑)写回领域对象。

**Rationale**:展开成 map 让「改一个参数 = 一行 diff」(FR-013),远比一坨 JSON 字符串可读。模型→文件→模型是**语义无损**(FR-011①允许文本不同但语义等价,规范 JSON 即语义等价);文件→模型→文件**字节稳定**(FR-011②:文件侧始终是排序 map)。`paramsJson` 为 null/空/非对象 → 省略 `params` 键。

## D7. 脚本体文件:扩展名映射(FR-001)

**Decision**:任务脚本体是**独立文件**,文件名 = `<task-slug>` + 由 `type` 映射的扩展名:`SQL→.sql`、`SHELL→.sh`、`PYTHON→.py`、`DATA_SYNC→.json`、`ECHO→.txt`,**未知 type → `.txt`**。`type` 本身存在 `*.task.yaml` 里(权威),扩展名只是编辑器高亮/可读性提示。`content` 为 null → 无脚本文件;为 ""(空串)→ 存在空脚本文件。

**Rationale**:满足「脚本以原生语言纯文本存在、可高亮可 lint」(US1-AC3),且 type 是开放字符串(Clarifications)故权威在 yaml、扩展名仅 hint。

## D8. 前向兼容(FR-016)

**Decision**:每份顶层文档(`project.yaml`/`*.task.yaml`/`*.flow.yaml`/`tags.yaml`)带 `formatVersion: 1`(整数)。读到**未知字段**→ **忽略**(不报错、不崩),记一条可选 warning;读到 `formatVersion` 高于当前已知 → 解析照常但记 warning。选择「忽略」而非「保留」:实现简单、旧工具优雅降级;代价是旧工具 round-trip 会丢新字段(可接受的前向兼容降级)。

**Rationale**:FR-016 明文「保留或忽略」二选一;忽略+版本标记是最简且足够的演进策略。

## D9. 落点与分层

**Decision**:B 落在 `dataweave-master` 模块新包 `com.dataweave.master.filecontract`(纯转换库,无 IO、无 Spring 依赖):
- `ProjectFileBundle` = `Map<相对路径, String(UTF-8)>` 的内存「文件树」,B **不碰真实文件系统**(IO 留给 C/D),保证纯单测可跑。
- `FileContract` facade:`serialize(ProjectExport) → ProjectFileBundle`、`deserialize(ProjectFileBundle) → ProjectImport`。
- `ProjectExport`/`ProjectImport` 聚合领域对象(TaskDef/WorkflowDef+Node/Edge/CatalogNode/Tag),供 C 接线到 repository。
- DTO(文件形状 record)、`DeterministicYaml`(SnakeYAML 封装)、各 mapper、`FileContractException`(带 file+locus)。

**Rationale**:B 是「契约 + 参考实现」,不含传输(C)与 CLI(D);纯内存 bundle 让 round-trip/确定性测试无需起 Spring、无需磁盘。Go CLI(D)按本契约文档(及 contracts/ 的 JSON Schema)独立实现同一格式。

## D10. 既有技术债(记录,不在 B 处理)

`WorkflowDagSnapshot` 误用 Jackson 2 注解(`com.fasterxml.jackson.annotation.JsonIgnoreProperties`)。**不在 B 范围**(B 不碰快照、不碰 Jackson)。记录备查,留给后续清理。B 自身不使用任何 Jackson 注解。

## 解决的 NEEDS CLARIFICATION

Technical Context 无遗留 NEEDS CLARIFICATION:YAML 引擎(D1)、映射策略(D2)、确定性(D3)、null 语义(D4)、字段范围(D5)、参数表示(D6)、脚本扩展名(D7)、前向兼容(D8)、分层(D9)均已裁定。spec 阶段的 3 项 + clarify 的 4 项决断作为输入约束已纳入。
