## 1. 解析引擎 ScheduleParamResolver

- [x] 1.1 在 `dataweave-master/application` 新建 `ScheduleParamResolver`,定义入口 `String resolve(String content, String bizDate, String paramsJson, BuiltInContext ctx)`,纯函数、无副作用、可独立单测。
- [x] 1.2 实现 `${<format>}` 格式化解析:支持 `yyyy`/`mm`/`dd` 任意组合(`yyyymmdd`/`yyyy-mm-dd`/`yyyymm`/`yyyy`),值取自 `biz_date`;遇到 `hh`/`mi`/`ss` 等天精度以外的 token → 解析失败。
- [x] 1.3 实现 `${<format>±N}` 整数偏移:`N` 的单位取 `<format>` 最小单位(天/月/年),支持 `${yyyymmdd-7*N}` 表示前 N 周;处理月末边界(月精度输出忽略日)。
- [x] 1.4 实现系统内置参数:`$bizdate`(≡`${yyyymmdd}`)、`$bizmonth`(跨月特判)、`$gmtdate`、`$jobid`、`$nodeid`、`$taskid`。
- [x] 1.5 实现自定义参数**递归展开(任意深度)**:`content` 里 `${name}` → `paramsJson` 中 `name` 的值;**值若仍含 `${...}` 继续递归展开**;带访问栈(visited set)做循环检测;字面嵌套 `${${...}}` 视为非法、解析失败。
- [x] 1.6 未解析 / 非法占位符抛出携带占位符名的异常(供上层转 `FAILED` + `errorMessage`),绝不静默放行。
- [x] 1.7 无任何占位符时原样返回 `content`(no-op,对存量任务零影响)。
- [x] 1.8 单元测试覆盖 spec 全部 scenario(17 例全过):格式化、偏移、月末、内置参数、bizmonth 跨月、多级递归值展开(`biz_pt=dt=${biz_dt}`)、循环引用、字面嵌套 `${${}}` 拒绝、非法 token、no-op、非内置 `$word` 原样保留(shell 变量)。

## 2. 接入执行链路

- [x] 2.1 确认 `SchedulerKernel.assign()` 第 194 行(`new DispatchCommand(..., contentOf(r), ...)`)为 in-process / distributed 唯一共同构造点(review 已确认)。
- [x] 2.2 `SchedulerKernel.assign()` 在 `contentOf(r)` 处调用 `resolveContentSafely(r, now)` 替换 `content`;新增 `paramsJsonOf(r)`(与 `contentOf` 同源,按版本优先 `task_def_version`)。
- [x] 2.3 替换失败 → `resolveContentSafely` 内 catch `UnresolvedPlaceholderException`,仅把该实例 `casTaskTerminal(DISPATCHED→FAILED)` + `errorMessage`;**不抛穿认领事务**(避免连坐同批次),不下发。
- [x] 2.4 `paramsJson` 固化为 Map `{"name":"expr"}`;resolver 内置极简 JSON 解析(master 模块刻意不引 Jackson,故手写扁平 String→String 解析),`NULL` / 空 / 非 JSON 对象视作空 Map。
- [x] 2.5 `triggerTestRun` 创建 WAITING TEST 实例后 `wake()` → 经 `SchedulerKernel` 认领 → 自动经 `resolveContentSafely` 替换;草稿(`taskVersionNo=null`)走 `contentOf`/`paramsJsonOf` 的 null 分支取 `task_def`。无需改 `triggerTestRun`。
- [x] 2.6 回归验证:`SchedulerConcurrencyTest` 3/3 通过(CAS 认领链 + SchedulerKernel bean 注入正常);既有 `SchedulerPreemptionTest`/`WorkflowGraphValidatorTest` 失败经 stash 确证为 workflow-canvas 引入,与本次无关。

## 3. 前端

- [x] 3.1 `task-edit-panel` 新增「调度参数（可选）」配置区:每行 name→expr 表单 + **快捷表达式预设下拉**(业务日期 / 前一天 / 前 7 天 / 上月 / 去年 / `$bizdate`…点选即填,免手敲)+ 删除,写入 `paramsJson`(`{"name":"expr"}`),保存时随 body 传。顺带修复既有 bug(`getById` 未解包 `ApiResponse.data`)。
- [x] 3.2 替换预览:bizDate 输入(默认昨天)+ 预览按钮 → 调 `POST /api/tasks/preview-params` → 显示替换结果,解析失败显示占位符名(按业务 `code` 判定,非 HTTP `ok`)。
- [x] 3.3 占位符语法帮助(参数区说明小字 + content placeholder 示例)。cron 输入:`task-edit-panel` 无 cron 字段(cron 在 `workflow_def`,属工作流编排画布),本变更 task 面板不涉及 cron。
- [x] 3.4 前端 `pnpm typecheck` EXIT 0 通过。

## 4. 验证与文档

- [x] 4.1 集成测试:`SchedulingParameterIntegrationTest.placeholder_substitutedAndExecuted` —— SHELL 任务 `echo dt=${yyyymmdd}` → 认领替换 → 执行,`task_instance.log` 含 `dt=20260611`。
- [x] 4.2 集成测试:`SchedulingParameterIntegrationTest.unresolvedPlaceholder_failsWithoutBlockingOthers` —— 未定义 `${nope}` → 实例 `FAILED` + `failure_reason` 含 `nope`,同批次正常实例照常 `SUCCESS`(不连坐)。
- [x] 4.3 文档:新增 `docs/scheduling-parameters.md`(语法速查 + cron 兼容说明)。
- [ ] 4.4 Browser Verification Gate（部分）:worktree 前端 `pnpm dev` + playwright 验证了 dev 启动 / 登录 / 0 console error / workspace 与「任务开发」视图切换渲染正常。**task-edit-panel 参数区的完整交互渲染未在本次打开**:worktree 前端连主 repo 压测后端(旧版,无 preview 端点),且 panel 经任务流「编辑任务」触发、`?open=task-edit` 深链不直接打开 side panel。建议合并 main 后或非压测环境(worktree 后端带 preview 端点)完整跑一次 panel 渲染 + 预览。核心已由 typecheck(EXIT 0)+ 后端 preview 端点 4/4 测试覆盖。
- [x] 4.5 后端 `./mvnw -q -pl dataweave-master compile` 零错误;master install 后 api 模块测试编译通过(SchedulingParameterIntegrationTest 2/2、SchedulerConcurrencyTest 3/3)。
