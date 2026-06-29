# Phase 0 Research: 列级 SQL 血缘解析

## D1. 列溯源算法:Calcite `getColumnOrigins` vs 手写 AST 映射

- **Decision**: 用 Calcite 关系代数 + `RelMetadataQuery.getColumnOrigins(rel, columnIndex)` 作为主路径。
- **Rationale**: `getColumnOrigins` 是 Calcite 内置的列血缘元数据,**原生穿透** JOIN / WITH CTE / UNION / 子查询 / 投影,返回每个输出列的 `RelColumnOrigin`(物理表 + 列序 + 是否派生 `isDerived`)。手写 AST 映射要自己处理别名作用域、CTE 展开、集合操作,极易漏与错。
- **Alternatives considered**:
  - 纯 SqlNode AST 遍历映射列:轻,但对 JOIN/CTE/表达式的作用域解析等于重写 Calcite 的 validator,不划算且脆。
  - 第三方血缘库(如 sqllineage/openlineage-sql):引入异构依赖与方言不一致,且与现有 Calcite 表级解析风格割裂。
- **代价**:`getColumnOrigins` 需要 **RelNode**,而 RelNode 需要**已校验的 SqlNode**,校验需要 **catalog(schema)**——引出 D2。

## D2. catalog 构造与"鸡生蛋"

- **Decision**: 定义 `ColumnLineageCatalog`(解析期输入接口),由 018 用 neo4j 里已注册的 `:Table`/`:Column` 元数据适配填充;构造 Calcite `Schema`/`Table` 供 validator 解析列引用与展开 `*`。源列元数据缺失时**降级**(见 D3)。
- **Rationale**: 列溯源必须知道源表有哪些列(尤其 `SELECT *` 展开、同名列消歧)。把元数据来源抽象成 `ColumnLineageCatalog` 接口,使本特性可纯单测(fixture catalog),并把"元数据从哪来"的耦合留给 018。
- **鸡生蛋处理**:首次见到的表可能尚无列元数据 → 该条 SQL 列级降级到表级;待该表列被注册(目标 `INSERT (col...)` 显式列、或后续任务声明)后再建的任务可精确解析。符合现有"建任务即逐步补全血缘"的节奏。
- **Alternatives**: 要求所有表预先注册全部列(不现实,发布前数据稀疏);运行时反射数据库 schema(越权、慢、违背"解析是增强"原则)。

## D3. 降级阶梯(韧性是硬不变量)

- **Decision**: 四级降级,任何情况都不抛阻断异常:
  1. 源/目标列元数据齐全 + Calcite 成功 → `ColumnEdge` confidence=**CONFIRMED**。
  2. 部分元数据缺失 / `*` 无法展开 / `getColumnOrigins` 对某列返回空(窗口函数/UDF)→ 对可解析列出 CONFIRMED,其余列 **AST 启发式按名匹配** confidence=**UNVERIFIED**。
  3. 完全无法校验/转换(DDL / 动态 SQL / 存储过程)→ 退回**仅表级**(委托既有 `SqlTableExtractor`),列级留空。
  4. 任何异常 → `try-catch` → 退表级 + `log.debug`,绝不上抛。
- **Rationale**: 沿用现 `SqlTableExtractor.extract` "绝不抛、失败 unparsed" 的范式(`TaskService.recordLineage` 也 try-catch)。血缘是增强,不能拖垮建任务/push。
- **Alternatives**: 严格模式(解析失败即报错)——直接违反主链路不阻断,否决。

## D4. transform 分类(DIRECT/EXPRESSION/AGGREGATE)

- **Decision**: 由列溯源的派生方式判定:`RelColumnOrigin.isDerived()==false` 且 1:1 → **DIRECT**;经标量表达式(`a+b`)且多源或函数 → **EXPRESSION**;经聚合算子(`SUM/COUNT/...`,源自 `Aggregate` RelNode)→ **AGGREGATE**。
- **Rationale**: 下游(020 前端 / 影响面)需要区分直传与加工,用于血缘可信度与影响判断。
- **Alternatives**: 不分类(只给边)——信息量不足,企业级血缘价值打折。

## D5. 列名规范化与表级一致

- **Decision**: 列/表名规范化(大小写、去引号、schema 前缀保留)**复用** `SqlTableExtractor` 的同一套规则(必要时抽公共工具)。
- **Rationale**: 列级 `:Column` 要正确挂到 018 建的 `:Table`/`:Column` 节点;若两边规范化不一致,列会挂错或挂空。这是接缝正确性的关键。

## D6. 列级 A×B 交叉校验

- **Decision**: Agent 在 `.task.yaml` 可**选择性**声明列级 I/O;与解析结果比对:都有且一致→CONFIRMED;仅解析有→CONFIRMED(SQL_PARSED);声明与解析冲突→**CONFLICT**(标记不静默丢)。沿用表级 A×B 的 source/confidence 语义。
- **Rationale**: 既利用 Agent 上下文精度,又用解析防护正确性;CONFLICT 暴露给治理而非掩盖。
- **Alternatives**: 只信解析 / 只信声明——各有盲区,交叉校验更稳。

## 未解决项

无 NEEDS CLARIFICATION:共享设计已锁定范围(仅 SQL 类型、列级本期做、LLM 解析未来)。
