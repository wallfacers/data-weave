## Why

DataWeave 的任务执行当前**完全不支持参数化**:任务 `content`(SQL / Shell 脚本)里的 `${xxx}` 占位符在执行时不会被替换;`TaskDef.paramsJson` 字段虽存在却从不被任何执行链路消费;而 `biz_date` 虽在实例生成时算好并存入 `task_instance`,却是个**孤儿字段**——没有任何占位符能引用它。结果是用户无法用单一任务定义表达「每天处理昨天的数据」这类最基本的周期性需求,日期只能硬编码进脚本,与「AI Agent 原生调度平台」的定位严重不符。

阿里 DataWorks 的「调度参数」是此类需求的事实标准:基于两个时间基准(业务日期 T-1、定时时间 T),用 `${...}` / `$[...]` 占位符语法 + 系统内置变量,在实例生成时把代码里的占位符冻结成具体值。DataWeave 的 `biz_date` 与定时触发点(`due`)已天然对应这两个基准,**只差一个替换引擎即可对齐**,数据基础已就位。

## What Changes

- **占位符替换引擎**:新增 `ScheduleParamResolver`(master `application` 层)。在任务 `content` **下发执行前**(`DispatchCommand` 构造处)调用,把模板里的占位符替换为具体值。一处替换,进程内 / 分布式两个执行网关与 worker 全部复用,零侵入。
- **业务日期语法 `${...}`**(基于 `biz_date`,天精度):
  - 格式化:`${yyyymmdd}`(=`20250314`)、`${yyyy-mm-dd}`、`${yyyymm}` 等;
  - 整数偏移:`${yyyymmdd-1}`(前天)、`${yyyymm-1}`(上月)、`${yyyy-1}`(去年)、`${yyyymmdd-7*N}`(前 N 周)。
- **系统内置参数**:`$bizdate`(≡`${yyyymmdd}`)、`$bizmonth`、`$gmtdate`、`$jobid` / `$nodeid` / `$taskid`。
- **自定义参数两级展开**:代码里写 `${dt}` → 在 `paramsJson` 里定义 `dt=${yyyymmdd-1}` → 系统递归展开为具体值。
- **冻结语义不靠存 content 文本**:替换输入(`biz_date`、`taskVersionNo` 指向的 content / paramsJson 快照)在实例生成时即冻结;执行时刻(含排队、重试延迟)不改变替换结果。
- **定时表达式**:保持现有 Spring 6 字段 cron(秒级,已强于 DataWorks)。补充与 DataWorks Quartz cron(`?` / `L` / `W`)的兼容性说明与前端校验。
- **out-of-scope**(留二期):`$[...]` 定时时间秒级语法、`add_months` 函数、补数据场景下 `$[...]` 的基准偏移。

## Capabilities

### New Capabilities
- `scheduling-parameters`: 任务执行时的调度参数(占位符)解析与替换——业务日期语法、系统内置参数、自定义参数两级展开、实例生成即冻结的替换语义、未解析占位符的处理。

### Modified Capabilities
（无。替换作为下游消费在 `WorkflowTriggerService` 接入,不改变 `cron-scheduler`、`instance-lifecycle` 等现有 capability 的 requirement 语义。）

## Impact

- **后端代码**:
  - `dataweave-master`:`application` 新增 `ScheduleParamResolver`(纯解析、无副作用、易测);`SchedulerKernel.assign()`(第 194 行 `contentOf(r)` 处)在构造 `DispatchCommand` 时调用 resolver 替换 `content`——in-process / distributed 两条执行路径的共同上游,一处替换两路复用。`biz_date` 由孤儿字段转为替换基准。
  - `dataweave-api` / `dataweave-worker`:**无改动**(替换在 master 侧完成,下发与执行链路透明)。
- **前端**:`task-edit-drawer` 增加「调度参数」配置区(参数名 → 表达式,预览替换结果)与占位符语法帮助文案。
- **数据库**:**无 schema 变更**(本期不存替换后的 content 文本)。
- **API 契约**:无新增端点;`TaskDef` 写入继续接受 `paramsJson`,语义增强(执行时被消费)。
- **AG-UI 协议**:无变更。
- **依赖**:无新增外部依赖(纯 Java 字符串 / 日期解析)。
