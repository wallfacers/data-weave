## 1. 解析引擎 ScheduleParamResolver

- [ ] 1.1 在 `dataweave-master/application` 新建 `ScheduleParamResolver`,定义入口 `String resolve(String content, String bizDate, String paramsJson, BuiltInContext ctx)`,纯函数、无副作用、可独立单测。
- [ ] 1.2 实现 `${<format>}` 格式化解析:支持 `yyyy`/`mm`/`dd` 任意组合(`yyyymmdd`/`yyyy-mm-dd`/`yyyymm`/`yyyy`),值取自 `biz_date`;遇到 `hh`/`mi`/`ss` 等天精度以外的 token → 解析失败。
- [ ] 1.3 实现 `${<format>±N}` 整数偏移:`N` 的单位取 `<format>` 最小单位(天/月/年),支持 `${yyyymmdd-7*N}` 表示前 N 周;处理月末边界(月精度输出忽略日)。
- [ ] 1.4 实现系统内置参数:`$bizdate`(≡`${yyyymmdd}`)、`$bizmonth`(跨月特判)、`$gmtdate`、`$jobid`、`$nodeid`、`$taskid`。
- [ ] 1.5 实现自定义参数**递归展开(任意深度)**:`content` 里 `${name}` → `paramsJson` 中 `name` 的值;**值若仍含 `${...}` 继续递归展开**;带访问栈(visited set)做循环检测;字面嵌套 `${${...}}` 视为非法、解析失败。
- [ ] 1.6 未解析 / 非法占位符抛出携带占位符名的异常(供上层转 `FAILED` + `errorMessage`),绝不静默放行。
- [ ] 1.7 无任何占位符时原样返回 `content`(no-op,对存量任务零影响)。
- [ ] 1.8 单元测试覆盖 spec 全部 scenario:格式化、偏移、月末、内置参数、bizmonth 跨月、多级递归值展开(`biz_pt=dt=${biz_dt}`)、循环引用、字面嵌套 `${${}}` 拒绝、非法 token、no-op。

## 2. 接入执行链路

- [ ] 2.1 确认 `SchedulerKernel.assign()` 第 194 行(`new DispatchCommand(..., contentOf(r), ...)`)为 in-process / distributed 唯一共同构造点(review 已确认)。
- [ ] 2.2 `SchedulerKernel.assign()` 在 `contentOf(r)` 处调用 `ScheduleParamResolver.resolve(...)` 替换 `content`;认领查询 `selectRunnable` 补取 `params_json` 到 `Row r`(bizDate/content 已在 r 上)。
- [ ] 2.3 替换失败 → `assign()` 内 catch 解析异常,仅把该实例 CAS 置 `FAILED` + `errorMessage` 写未解析占位符名;**不抛穿认领事务**(避免连坐同批次),其余任务照常下发。
- [ ] 2.4 `paramsJson` 固化为 Map `{"name":"expr"}`;resolver 用 Jackson(Jackson 3,`tools.jackson.databind`)反序列化,`NULL` / 空 / 非法 JSON 视作空 Map。
- [ ] 2.5 确认 `triggerTestRun` 执行路径:若经 `DispatchCommand` 则自动复用替换;若同步直接执行,则在该路径也接入 resolver。

## 3. 前端

- [ ] 3.1 `task-edit-drawer` 新增「调度参数」配置区:参数名 → 表达式的可增删表单(name→expr),写入 `paramsJson`(`{"name":"expr"}`)。
- [ ] 3.2 占位符替换预览:选定一个样例 `biz_date`,实时展示 `content` 替换后结果。
- [ ] 3.3 占位符语法帮助文案(`${yyyymmdd-1}` / `$bizdate` 等);cron 输入加 Quartz `?`/`L`/`W` 兼容性提示与 Spring 解析校验。
- [ ] 3.4 前端 `pnpm typecheck` 通过。

## 4. 验证与文档

- [ ] 4.1 集成测试:`@SpringBootTest` / WebTestClient 验证 cron 触发 → 实例 `biz_date` 冻结 → `content` 占位符替换 → 实际执行内容正确。
- [ ] 4.2 集成测试:未解析占位符导致实例 `FAILED` 且 `errorMessage` 命名占位符,且**不连坐**同批次其他任务。
- [ ] 4.3 文档:在 `docs/architecture.md` 或 OpenSpec 补「调度参数语法」与 cron 兼容性说明。
- [ ] 4.4 Browser Verification Gate:`task-edit-drawer` 参数配置区渲染、预览生效、console 无 error。
- [ ] 4.5 后端 `./mvnw -q -pl dataweave-master compile` 零错误;改动模块 install 后全量 `compile` 通过。
