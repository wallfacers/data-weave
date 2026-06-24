## Context

DataWeave 已有大量驾驶舱「零件」：`cockpit-view`（`/api/ops/summary`）、`metrics-view`（四层指标）、`self-diagnosis`（根因+建议+一键 fix）、SSE 双流（`realtime-streams`：logs + DAG events 节点实时变色）、`@xyflow/react` DAG 画布、`dataweave.ui.open` 视图召唤。但缺一根把零件串成「活系统」的主轴。

探查实锤的三大缺口与现状约束：

- **跨系统血缘不存在**：仅 `workflow_node`/`workflow_edge` 描述「单工作流内」DAG，`workflow_dependency` 描述「跨工作流任务级」依赖，二者都不到表级。`metric_lineage` 是指标中心（`metric_id → downstream`），不能承载 `表→任务→表`。
- **Agent 建任务时不掌握表名**：`create_task`（MCP）仅接 `name/content/cron`；`IntentRouter.createTask` 正则抽 SQL；`createAndOnline` 不设 `datasource_id/target_datasource_id`。SQL 全文躺在 `task_def.content`（VARCHAR 4000）。
- **无同步行数采集**：`task_instance` 无 `row_count`，`ExecutionResult` 仅有 stdout/stderr 文本，「今日同步 N 行」无处可取。

可复用的正向信号：`task_def` 已有 `datasource_id/target_datasource_id`（数据源级兜底）；`atomic_metrics` 已有 `source_table` 字段（存物理表名有先例）；`SlaService` 已算历史就绪时间中位数基线（ETA 可复用）；`mock`/`workhorse` 双 Agent 模式（结构化 io 声明在 workhorse 下天然成立）。

## Goals / Non-Goals

**Goals:**
- 建一根「表为节点、任务为边」的二部图血缘脊柱，跨数据源、可上下游双向查询，建任务即生成（不依赖任务先跑）。
- 让血缘成为 Agent 施工的结构化副产品，而非事后 SQL 考古；并对 Agent 声明做 SQL 解析交叉校验，给每条边可信度标签。
- 把驾驶舱第一屏从「统计卡拼盘」演进为「跨系统活血缘图为中心」，非开发人员打开即懂「数据流到哪、同步多少、还要多久」。
- 分期可交付：Phase 0 外壳即可演示，Phase 1 脊柱，Phase 2 运行态数据，每期独立可用、向后兼容。

**Non-Goals:**
- 不替换/迁移 `metric_lineage`（指标域保持原样，二者并存）。
- 不做字段级（column-level）血缘——MVP 止于表级。
- 不做血缘的历史版本回放/时间旅行（本期只维护「当前有效」拓扑 + 最近运行态）。
- 不在 mock 模式追求 io 声明完备——mock 下血缘以 SQL 解析为主、降级到数据源级。
- 不引入图数据库——二部图存关系型表，节点规模（百级表）下足够。

## Decisions

### D1. 血缘建模为「表-任务」二部图，设计态 / 运行态两层

节点是表，边是任务。任务读 N 表、写 M 表。两层拆分：

```
data_table          节点：(datasource_id, qualified_name) 唯一；layer(ODS/DWD/DWS/ADS) 可空
task_table_io       设计态边：task_def_id × table_id × direction(READ/WRITE) × source × confidence
task_run_table_io   运行态边：task_instance_id × table_id × direction × row_count × bytes × biz_date
```

- **设计态 = 图的形状**（建任务即写入，给拓扑），**运行态 = 图上流动的数据**（每次跑批写入，给吞吐/行数/ETA 样本）。
- 驾驶舱主舞台 = 设计态画骨架 + 运行态做动画，正好吻合「活血缘图」。
- **为何不复用 `metric_lineage`**：它是指标中心多态 downstream，强塞表级会污染语义；新建干净二部图，指标域不动。
- **为何不直接在 `task_def` 加 JSON 列存 io**：图遍历要「谁写了表 X」的反向查询，JSON 数组无法高效 join；规范化子表 `task_table_io` 才支持双向遍历。
- **替代方案（弃）**：单张大宽表 `table_lineage(source_table, task_id, target_table)` 直接存边——读多表/写多表时笛卡尔展开、行数膨胀且语义糊；二部图按 direction 拆更干净。

### D2. 设计态血缘三来源 + 可信度标签（A 主 B 校 C 补）

`task_table_io.source ∈ {AGENT, SQL_PARSED, FORM}`，`confidence ∈ {CONFIRMED, UNVERIFIED, CONFLICT}`：

- **AGENT（主）**：workhorse 真脑建任务时，结构化输出 `reads[]/writes[]`。Agent 写的 SQL，最清楚读写谁；SHELL/Python 任务也能声明（解析做不到的它能做）。
- **SQL_PARSED（自动校验）**：`type=SQL` 时，上线流程解析 `content` 提取 `FROM/JOIN`→reads、`INSERT INTO/CREATE TABLE AS/MERGE`→writes。
- **A×B 交叉判定**：两者一致 → `CONFIRMED`；仅 A 有、解析未证实 → `UNVERIFIED`；A 与解析矛盾 → `CONFLICT`，驾驶舱打「待复核」。**把「写操作无旁路必过闸」哲学延伸到血缘可信度**——连 Agent 自报的血缘也要被验证。
- **FORM（兜底）**：建任务表单手选输入/输出表，或仅有 `datasource_id` 时退化为数据源级粗粒度节点。
- **为何不纯靠 SQL 解析（传统做法）**：解析对 SHELL/存储过程/动态 SQL 抓瞎且滞后；Agent 声明覆盖面更广且即时。解析退居校验位，扬长避短。
- **模式差异**：workhorse 下 A 为主（杀手锏）；mock 下无结构化脑，降级 B 为主、再降到数据源级。此差异在 spec 中显式声明，不假装 mock 完备。

### D3. `create_task` 链路扩展 io，经现有闸门

- MCP `create_task` schema 增可选 `datasourceId / targetDatasourceId / reads[] / writes[]`；`TaskService.createAndOnline` 签名扩展，落 `task_def` 数据源字段 + 写 `task_table_io`。
- 仍走 `GatedActionService.submit` → `PolicyEngine`，不开旁路；血缘写入是建任务事务的一部分。
- 向后兼容：旧调用不传 io → 落库为空 → 图降级到数据源级或缺省节点，不报错。

### D4. 运行态行数采集在 worker 执行后上报

- SQL 任务：JDBC `executeUpdate` 的 `updateCount` 给 write 行数；read 行数对 `SELECT...INTO`/`INSERT...SELECT` 取受影响行；复杂场景退化为不采集（留空，不猜）。
- 通过现有 worker→master 回报通道带上 `{table, direction, rowCount, bytes}`，落 `task_run_table_io`。
- 「今日同步量」= 当日 `task_run_table_io.row_count` 聚合；节点吞吐 = 近期运行态滑动统计。
- **为何不在 master 侧解析**：行数是执行副作用，只有 worker 持有真实 `updateCount`；master 拿不到。

### D5. ETA 复用 SlaService 基线，不新建预测引擎

- 节点 ETA = `历史成功运行时长中位数（近 N 次） − 当前实例已耗时`，下界裁剪到 0。
- 复用 `SlaService` 既有的「近 N 次就绪时间中位数」算法，新增运行时长维度，暴露轻量预测端点。
- 冷启动（无历史样本）→ ETA 显示「估算中」而非编造数字（遵守「无省略号表进行中」与诚实呈现）。

### D6. 前端活血缘图复用既有栈，第一屏重心迁移

- 主舞台用 `@xyflow/react`（已集成），节点按 `layer` 分层做横向 ELK/dagre 布局；SSE 复用 `useEventSource`（直连 `SSE_BASE`，不走 Next rewrite 以免缓冲）+ `runStateDot` 着色映射。
- 节点贴 ETA / 根因一句话；右栏举手台复用 `self-diagnosis` 的 `rootCause/suggestions/fix`，按 `outcome` 分流（参考既有「回滚等写操作默认 L2 审批」教训，不只看 `code===0`）。
- 点节点 → `dataweave.ui.open` 下钻到现有 `workflow-instance-detail`/`instance-log`/`metrics`，现有视图从「平级 tab」降格为「血缘节点详情」。
- `cockpit` 视图重构：统计卡降为顶条聚合，主体让位活血缘图。Phase 0 主舞台先并排现有单工作流 DAG，Phase 1 换跨系统图。

### D7. 规模可控的全局图：节点折叠 + 按需展开

- 百级表全量渲染可接受；上千则按 `layer`/数据源/目录折叠为「层包」，点开展开子图，避免 ReactFlow 卡顿。
- 全局血缘查询端点支持「以某表/某任务为中心，N 跳邻域」的子图拉取，前端按需取数而非一次拉全图。

## Risks / Trade-offs

- **[Agent 声明的 io 不准/幻觉] → ** SQL 解析交叉校验 + `CONFLICT/UNVERIFIED` 可信度标签显式暴露，不把未证实血缘当事实呈现；CONFLICT 进右栏待复核。
- **[mock 模式血缘质量差] → ** spec 显式声明 mock 降级到 SQL 解析/数据源级，不假装完备；这是 workhorse 模式的差异化卖点，文档化而非掩盖。
- **[SQL 解析覆盖不全：动态 SQL/方言/SHELL] → ** 解析失败不阻断建任务，退化为 AGENT-only（UNVERIFIED）或数据源级；解析是增强非前置依赖。
- **[运行态行数对复杂 SQL 取不到] → ** 留空而非猜测；「今日同步量」标注为「已采集任务」口径，避免误导。
- **[全局图节点爆炸卡死前端] → ** D7 折叠 + N 跳邻域按需取数；先在真实数据规模下做浏览器验证（强制门）。
- **[第一屏重构影响现有 cockpit 用户] → ** 分期：Phase 0 不动数据来源只搭壳，统计卡迁顶条保留信息；向后兼容，血缘缺失时降级。
- **[H2/PG 双方言血缘 DDL] → ** 遵循既有教训：拼接用 CONCAT、DDL 用 IF NOT EXISTS、两库各测一遍。
- **[JSQLParser 新依赖] → ** 仅用于解析校验，隔离在 master/worker 解析工具类；解析异常吞掉降级，不影响主链路。

## Migration Plan

1. **Phase 0（外壳，可演示）**：前端搭驾驶舱新布局（顶条聚合 + 右栏举手台复用 self-diagnosis + 主舞台并排现有单工作流 DAG）。纯前端复用，无 DB 变更，无破坏。
2. **Phase 1（脊柱）**：建 `data_table/task_table_io` 表（H2/PG）；`create_task`/`createAndOnline` 扩 io 落库；引入 SQL 解析做 A×B 校验；新增跨系统血缘查询 REST；主舞台换真·活血缘图。旧任务无血缘 → 数据源级降级。
3. **Phase 2（大脑）**：建 `task_run_table_io`；worker 上报行数；顶条「今日同步量」+ 节点 ETA（复用 SlaService）+ 吞吐动画。
4. **回滚**：每期独立。Phase 1/2 的新表与新端点为增量，前端视图回退到 Phase 0 壳或原 `cockpit` 即可；不改动调度内核与 `metric_lineage`，无破坏性迁移。
5. **验证门**：触及 AG-UI/驾驶舱视图/SSE → 强制浏览器验证门（CopilotChat 渲染、SSE 实时变色、节点下钻、console 无错）；H2+PG 双库各跑一遍血缘读写。

## Open Questions

- **OQ1（已定 2026-06-24）**：`data_table.layer`（ODS/DWD/DWS/ADS）走**命名前缀推导 + 可覆盖**——按表名前缀（`ods_`/`dwd_`/`dws_`/`ads_`，大小写不敏感）自动判层，允许 Agent 声明或表单手选覆盖。前缀不匹配 → layer 留空（图中归入未分层）。
- **OQ2**：跨工作流的「表级依赖」是否要与现有 `workflow_dependency`（任务级）打通互推？本期先并存，血缘图只读 `task_table_io`，依赖判定仍走调度内核。
- **OQ3（已定 2026-06-24）**：SQL 解析库选 **Apache Calcite**（带方言、解析能力强）。封装在 master 解析工具类内，解析异常吞掉降级为 UNVERIFIED；接受其较重的依赖成本换取方言覆盖。
- **OQ4**：「同步行数」对非 INSERT 类（如外部同步工具、SHELL 拉数）如何采集？本期仅覆盖 SQL `updateCount`，其余留空，待 Phase 2+ 接 worker sidecar 上报。
