## Context

项目有 11 个表格,仅 `periodic-instances-panel`(周期任务流实例)是生产级:三段式布局、双表共享 colgroup 固定表头、`DwScroll`、`Pagination`、多选批量、智能默认 + 多维筛选。其余表格状态参差:

- 标准 `<Table>` 但无分页/固定表头/筛选:周期任务流(仅 5 列)、手动任务流、任务定义、任务实例、freshness。
- 手写 `<table>`:`datasources-view`、`settings-view`(用户/角色/项目)。
- 后端能力分档:任务实例/任务定义已 server 分页+多维筛选(就绪);周期/手动任务流、数据源/用户/角色/项目全量裸返回;补数据 Run 列表是 `待 Stream A` 桩;数据新鲜度无后端接口、纯前端派生。

已有可复用基建:`components/ui/table.tsx`、`components/ui/pagination.tsx`(server 分页 UI 齐全)、`components/ui/dw-scroll.tsx`(OverlayScrollbars 封装)、`components/ui/select.tsx`(DropdownSelect)、`date-picker`。`DESIGN.md` 已规范滚动条与 Markdown 表格,但无结构化数据表标准。

约束:前端栈门(hugeicons、base-style render prop、语义 token)、设计契约门(改 DESIGN.md 为源 + 同步 globals.css + `pnpm design:lint`)、i18n 三规(静态 UI 文案走 next-intl 双 bundle,后端错误走 `BizException`)、Spring Boot 4 / Jackson 3、闸门不可绕过、浏览器验证门。

## Goals / Non-Goals

**Goals:**

- 沉淀 `DataTable<T>` + `DataTableToolbar` 两个高阶组件,以标杆为蓝本,一次定义处处统一。
- 11 个表格全部迁移到组件,视觉与交互一致。
- 组件具备 client/server 双模式能力(可配置);本次所有表配 server 模式,筛选/分页接后端真实查询,**无 client 端假兜底**。
- 每个表按页面诉求配「有价值」筛选:智能默认 + 语义化快捷预设 + 主/高级分层 + 克制。
- 后端补齐所有列表端点的真实筛选 query 参数与分页;补数据桩→真实现;数据新鲜度后端真做聚合接口。
- `DESIGN.md` 写入结构化表格规范条款。

**Non-Goals:**

- 不做表格列拖拽、列显隐持久化、Excel 导出等高级特性(后续迭代)。
- 不重构左侧 catalog-tree 的目录筛选(任务定义表的目录维度复用既有 tree,不在工具栏重复)。
- 不改 AG-UI 协议、不改聊天台、不改调度内核。
- `result-table`(Agent 结构化结果表)是动态列只读展示,不属于本次「管理/监控列表」统一范围,保持现状。

## Decisions

### D1: 双层组件 `DataTable` + `DataTableToolbar`,而非单巨组件

`DataTableToolbar` 独立,接 `FilterDef[]` + 预设,负责筛选 UI 与值管理;`DataTable` 负责布局/列/分页/选择/空加载。二者通过受控的筛选状态对象联动。

- **为何**:筛选诉求差异大且会单独演化(预设、分层),拆开便于复用与测试;布局与筛选关注点分离。
- **替代**:单组件全包 → 配置项爆炸、难测;放弃抽象逐表手抄 → 用户已否决(要根治统一)。

### D2: 列与筛选用配置数组(`ColumnDef<T>` / `FilterDef`),取代手写 JSX

`ColumnDef`:`{ key, header(i18n key), widthPct, align, cell?(row)=>ReactNode }`,列宽渲染为 `colgroup` 百分比 + `table-fixed`。`FilterDef`:`{ key, label, kind: search|segmented|multiSelect|dateRange|toggle, options?(静态或异步源), tier: primary|advanced, default? }`。预设:`{ label, set: Partial<FilterValues> }`。

- **为何**:声明式列/筛选是「一次定义处处统一」的载体,也让 DESIGN.md 规范可落到类型上。
- **替代**:引入 TanStack Table → 体量大、与现有 base-style/DwScroll 双表固定表头方案磨合成本高,不必要。

### D3: 双模式契约 —— 组件具备 client/server,本次一律 server

`DataTable` 接 `mode: "client" | "server"`。server 模式下组件不持有全量,把 `{ ...filterValues, page, size }` 交给调用方 `fetcher(query) => Promise<Page<T>>`;client 模式对传入数组本地筛选分页。对外 `FilterDef`/列声明在两模式下完全一致。

- **本次决策**:11 个表全部 server。即便小表(角色/项目)数据量不大,也走后端查询以杜绝「假筛选」技术债,并保持契约一致。client 能力保留给未来纯前端派生场景。
- **风险**:小表 server 化略增请求,但语义统一收益更大。

### D4: 后端统一列表查询契约 —— `Page` 信封 + query 参数,向后兼容

所有列表端点统一:有筛选/分页参数 → 返回 `Page`(content/totalElements/page/size);无参 → 兼容旧的全量数组(已有端点如 instances 已是此模式)。Repository 层用 JdbcTemplate 动态拼 WHERE(项目既有 `TaskService` 动态查询先例),避免引入新查询框架。

- **为何**:与既有 `GET /api/ops/instances` 行为一致,减小前端适配面;兼容旧调用方不破坏。
- **替代**:全部强制 Page、删除全量分支 → 破坏 freshness 派生等既有调用,且无必要。

### D5: 数据新鲜度后端化

新增聚合端点(归 ops/metrics 域):后端 SQL 按任务聚合 `MAX(finished_at) WHERE state=SUCCESS`,计算分档,支持分档筛选/名称搜索/排序/分页。前端 `freshness-view` 改为 server 模式消费,删除前端 `Date.now()` 派生逻辑。

- **为何**:前端派生无法 server 筛选分页、且 `Date.now()` 在大列表下重复计算;后端聚合是「真筛选」的前提。
- **注意**:分档阈值(6h/24h)与前端现状一致,挪到后端常量,避免双处漂移。

### D6: 智能默认 + 语义化预设是「专业 vs demo」的分水岭

监控/分诊型表(实例/补数据/新鲜度)默认停在「最该看的一屏」并提供预设 chips;管理/检索型表(任务流/任务定义/数据源/用户/角色/项目)以搜索为主、默认全量按最近/名称排。角色等小表只给搜索框(克制),不堆筛选。每表筛选维度见 proposal 与 specs。

- **为何**:用户明确要求「给人用、不是光秃秃 demo」;堆砌筛选数量不等于专业,理解诉求 + 默认 + 预设才是。

### D7: i18n 与设计契约落位

筛选标签、列头、预设名、空/加载文案 → 前端 next-intl 双 bundle(`ops` 及各 view 命名空间,键集对齐);后端校验类错误 → `BizException` + code。`DESIGN.md` 先改(源)再同步 `globals.css`(若涉及变量),`pnpm design:lint` 通过。无 `…` 表「进行中」。

## Risks / Trade-offs

- **改造面广(11 表 + 多后端端点)** → 分阶段:先落组件 + DESIGN.md 规范 + 标杆回填验证,再按「就绪后端(实例/任务定义)→ 中改(周期/手动/数据源/用户/项目/角色)→ 桩补真(补数据)→ 新建(新鲜度)」推进,每步可独立验证。
- **后端动态查询易出 SQL 方言坑(H2/PG)** → 遵循既有约定:拼接用 `CONCAT` 不用 `||`,两库各测一遍;复用 `TaskService` 动态查询写法。
- **CORS 漏 PATCH / 列表新增 GET 参数** → 新增均为 GET query,低风险;仍按浏览器验证门实景跑通。
- **批量操作误判成功** → 强制按闸门 `outcome` 分流(EXECUTED/PENDING_APPROVAL/REJECTED),不以 `code===0` 判定。
- **server 化小表增加请求** → 可接受,换取契约统一与零假筛选。
- **freshness 阈值双处漂移** → 阈值收敛到后端单一来源。
- **i18n 双 bundle 键集不齐导致运行时报错(过往踩过)** → 改完跑键集对齐校验 + typecheck + 浏览器实跑。

## Migration Plan

1. **基建先行**:实现 `DataTable` + `DataTableToolbar` + `ColumnDef`/`FilterDef` 类型;补 `Pagination`/`DwScroll` 缺口;`DESIGN.md` 写入表格规范 + `design:lint`。
2. **标杆回填**:用新组件重写 `periodic-instances-panel`,作为等价回归基准(交互/视觉不退化),browser gate 验证。
3. **就绪端点接入**:任务实例、任务定义(后端已支持,补缺维度)。
4. **中改端点**:周期/手动任务流、数据源、用户、项目、角色(后端加 query 参数 + 分页 + Repository 查询方法;前端接入)。
5. **桩补真**:补数据 Run 列表真实现 + 筛选。
6. **新建**:数据新鲜度后端聚合端点;前端改 server 模式。
7. **手写表迁移**:`datasources-view`、`settings-view` 三表。
8. **整体验证**:后端 JUnit/WebTestClient(带 JWT)、前端 vitest、浏览器验证门;i18n 键集对齐;`design:lint`、`typecheck`、各模块 `compile`。

回滚:组件为新增、端点参数向后兼容,可按表逐个回退到旧渲染而不影响他表。

## Open Questions

- 数据新鲜度聚合端点归属:`OpsService` 还是 `SchedulerMetrics`/独立 service?倾向 ops 域单独 service,实现时定。
- 周期任务流「最近触发结果」数据来源:复用 `lastFireTime` + 关联最近实例 state,还是新增冗余字段?倾向查询期 join 最近实例,避免加列;若性能不足再冗余。
- 各表 `page` 起始基准不一(任务定义 0-based、实例 1-based):统一对前端 `Pagination` 适配层归一,实现时确认每端点基准。
