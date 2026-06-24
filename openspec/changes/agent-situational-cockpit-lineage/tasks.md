## 1. Phase 0 — 驾驶舱外壳（纯前端复用，可演示，无 DB 变更）

- [x] 1.1 在 `frontend/lib/workspace/` 注册新视图来源：`cockpit` 视图重构为「顶条聚合 + 主舞台 + 右栏」三区布局骨架（views.ts/registry.tsx 不新增 view，原地重构 cockpit-view.tsx）
- [x] 1.2 顶条聚合条组件：复用 `/api/ops/summary` + `/api/ops/metrics`，展示健康度/运行·排队·异常计数（今日同步量/最迟 ETA 先占位「估算中」）
- [x] 1.3 右栏「Agent 举手台」组件：复用 `/api/diagnosis` 列表，每条渲染根因+建议+「让它处理/我看看/忽略」按钮（复用 DiagnosisCard/FixActions）；处理动作按 `outcome` 分流（PENDING_APPROVAL 显示「已提交审批」，不只看 code===0）
- [x] 1.4 主舞台形态：占位舞台被 4.2 直接取代——cockpit-view 主舞台即渲染真·跨系统血缘图（`<LineageGraph/>`，layer 列布局 SOURCE/ODS/DWD/DWS/ADS + 方向流边），无需中间占位态，抛弃型代码省去
- [~] 1.5 节点下钻：随 4.2 节点语义由「任务」变为「表」，下钻目标语义随之改变（表 → 产出它的任务 → 最近实例日志/详情），需「表详情」落点设计；当前血缘图节点可视交互已就绪，富下钻留作增量（依赖 表→producing-task→latest-instance 链路设计）
- [x] 1.6 i18n：新增 `lineageCockpit` 命名空间双语 key（zh-CN/en-US 键集一致，i18n:lint 通过），顶条/举手台/空状态文案，无省略号表进行中
- [x] 1.7 浏览器验证门：CopilotChat 渲染正常、驾驶舱三区（顶条聚合/主舞台/右栏举手台）呈现、6 项文案断言全过、console 0 错（SSE 节点实时变色/下钻随 1.4/1.5 延到 Phase 1 验）

## 2. Phase 1 — 血缘脊柱：数据模型与建表

- [x] 2.1 新建 `data_table` 表 DDL（H2 + PG 双方言，schema.sql `mode:always` 幂等重建配 DROP）：`id/datasource_id/qualified_name/layer/...`，唯一索引 `datasource_id+qualified_name`（datasource_id=0 兜底未知源，规避 NULL 唯一性差异）
- [x] 2.2 新建 `task_table_io` 表 DDL：`id/task_def_id/task_version_no/table_id/direction/source/confidence/...`（运行态 `task_run_table_io` 一并建好供 Phase 2）
- [x] 2.3 master 领域层：`DataTable`/`TaskTableIo` 领域模型 + Spring Data JDBC Repository（`deleteByTaskDefId` 替换语义）
- [x] 2.4 `LineageGraphService`（新建，独立于指标域 `LineageService`）：写设计态边（upsert 表节点 + 替换旧边）、命名前缀推导 layer、全局图、表→表流边推导、N 跳邻域子图、上下游双向 BFS
- [x] 2.5 `dataweave-master` 编译通过（JDK25 export 后 `-pl dataweave-master compile` EXIT=0）+ `install -DskipTests`

## 3. Phase 1 — 建任务链路接入血缘（三来源 A×B）

- [x] 3.1 引入 SQL 解析依赖（**Calcite 1.40.0**，OQ3 定）于 master，封装 `SqlTableExtractor`：从 content 提取 FROM/JOIN→reads、INSERT/MERGE→writes，排除 CTE 名；解析异常吞掉降级（6 单测过）
- [x] 3.2 `TaskService.createAndOnline` 加 io 重载 `datasourceId/targetDatasourceId/reads[]/writes[]`，落 `task_def` 数据源字段 + 调 `recordDesignTimeIo`；4 参旧签名委托（向后兼容）
- [x] 3.3 A×B 交叉校验 `buildEdges`：AGENT×SQL_PARSED 比对（大小写不敏感）→ CONFIRMED/CONFLICT/UNVERIFIED；仅解析→SQL_PARSED/CONFIRMED；mock 模式自动以解析为主（5 单测过）
- [x] 3.4 `McpToolRegistry.create_task` schema 增 `datasourceId/targetDatasourceId/reads/writes`，管道编码 `@io:` 头穿过闸门，executor 解码调 8 参 createAndOnline（向后兼容旧命令）
- [x] 3.5 `IntentRouter.createTask`（mock）免改即产出血缘——createAndOnline 自动解析 content；未声明 io 且不可解析时不记录，向后兼容
- [x] 3.6 后端单测：`SqlTableExtractorTest`(6) + `TaskServiceLineageTest`(5) 覆盖解析、A×B 三态、无 io 降级（全过；二部图查询 REST 测试见 4.1）

## 4. Phase 1 — 血缘查询 REST 与前端活血缘图

- [x] 4.1 新增血缘查询 REST `LineageGraphController`：全局图 + N 跳邻域 + 上下游（带 confidence）；`LineageGraphEndpointTest`(2) h2 全栈契约过（坑：cat>> 追加的 DDL 被 linter 剥掉致 data_table 不存在→500，改用 Edit 重加）
- [x] 4.2 前端 `lineage-graph.tsx`（ReactFlow 只读）：主舞台换为真·跨系统血缘图，节点按 layer 列布局（SOURCE/ODS/DWD/DWS/ADS），接 `/api/lineage/graph`；接入 cockpit-view 主舞台
- [x] 4.3 CONFLICT/UNVERIFIED 边视觉标记：CONFLICT 红虚线、UNVERIFIED 黄虚线、CONFIRMED 流动实线 + 右上图例（双语 i18n）
- [~] 4.4 全局图规模控制：N 跳邻域 REST 已就绪（`/tables/{id}/neighborhood`）；前端 layer 折叠暂缓（当前规模小，节点 5/百级可直渲），留待数据量上来再接
- [x] 4.5 浏览器验证门：8 项断言全过（态势标题/4 血缘节点/2 图例/ReactFlow 5 节点渲染/无裸 i18nKey），console 0 错，CONFLICT 红虚线实证；后端 PG 实时供数（data.sql 种 ODS→ADS 流）

## 5. Phase 2 — 运行态行数采集与 ETA

- [x] 5.1 新建 `task_run_table_io` 表 DDL（H2+PG）：`task_instance_id/table_id/direction/row_count/bytes/biz_date`（随 2.2 一并建好）
- [~] 5.2 worker 执行后采集读/写行数（JDBC updateCount / 受影响行），经现有 worker→master 回报通道带回；无法可靠采集时留空不猜 —— 通道与落库已就绪（task_run_table_io + 聚合端点用真数据演示），worker 端 updateCount 采集需真实执行器接线，留待 mock→workhorse 切换后接（当前 mock 不产真行数，种子兜底）
- [x] 5.3 master 落 `task_run_table_io`；「今日同步量」聚合端点（`LineageGraphService.syncedRowsLatestDay` 按「已采集任务」口径，最近 biz_date WRITE 行数和，row_count NULL 不计）+ `/api/lineage/sync-summary` REST，接入顶条 TopStat（null→「估算中」，亿/万格式化）
- [x] 5.4 ETA 预测：`SlaService.durationMedianMs`（近 N 次 SUCCESS 运行时长中位数，奇偶分支）+ `predictLatestEta`（运行中实例最迟预计完成，超期按即将完成）；`/api/ops/eta-summary` 轻量端点；冷启动无样本返回 null→前端「估算中」
- [x] 5.5 顶条「今日同步」「最迟看板 ETA」接真实数据（亿/万 Intl 紧凑格式 + 约 Nmin/h/即将完成）；血缘图节点贴 ETA + 吞吐粒子动画留作增量（当前节点级运行态数据未逐表落，顶条聚合已先验证链路）
- [x] 5.6 后端测试：`SlaEtaPredictionTest`(4) 覆盖中位数奇/偶、冷启动留空、最迟 ETA 预测；浏览器验证门回归：顶条今日同步 1.86亿 + 最迟 ETA 约 20min（真预测随时间递减）+ 血缘图 5 节点 + CONFLICT 红虚线，6 项断言全过 console 0 错

## 6. 收尾与归档

- [x] 6.1 H2 与 PG 双库各跑一遍血缘读写 + 驾驶舱端到端，确认方言无坑：H2 全栈 `LineageGraphEndpointTest`(4，含 sync-summary 186M / eta-summary 非空)；PG 重启 + 三端点(graph 5 节点 / sync 186M / eta 1785s)实证。**修幂等坑**：data_table/task_table_io/task_run_table_io（含他人 driver_jars）裸 CREATE 缺配套 DROP，mode=always 在持久 PG 重启撞 already exists → 补 DROP 块（44 CREATE/40 DROP 缺口 4 张补齐）
- [x] 6.2 更新 CLAUDE.md「Knowledge Base Navigation」加 4 行导航：表级血缘(LineageGraphService+SqlTableExtractor+Controller)、态势驾驶舱视图(cockpit-view+lineage-graph)、ETA 预测(SlaService+端点)
- [ ] 6.3 `openspec validate` 通过 → `/opsx:archive` 归档（合并 delta 至 base specs）
