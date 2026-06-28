# 012 · 平台占位符语法迁移到 `{{...}}`(shell/SQL 安全)

**状态**: spec(设计已定,待实现)
**作者**: 架构(orchestrator) · 2026-06-28
**前置**: e568c38 合并(origin 完整 cron + 实例运维)入 Weft 后暴露的合并缝

## 1. 背景与问题(合并缝)

Weft 的 `ScheduleParamResolver`(特性 scheduling-parameters)把任务内容里的 `${...}` 与裸 `$word`
当作**平台调度占位符**解析,**未定义即抛 `UnresolvedPlaceholderException`**——这是刻意契约:
预览端点(`TaskController:222`)靠它把 typo 反馈给用户;`SchedulerKernel:411`/`OpsService:926`
catch 后置实例 FAILED。

但 origin 的「真实化」种子(`c4213e0`)是**整段 bash/SQL 脚本**,内容大量使用 shell 自身的
`${VAR}` 与 bash 展开:

```bash
#!/bin/bash
BIZ_DATE=${bizdate:-$(date -d "yesterday" +%Y%m%d)}
PARTITION="dt=${BIZ_DATE}"
sqoop import --username ${SRC_USER} --table ${TABLE} ... ${COUNT}
```

origin/main 上**没有这个解析器**,脚本原样交执行器 → 测试绿。合并后 Weft 解析器拦截 origin 脚本,
遇到第一个 shell 变量(`${BIZ_DATE}` 等)即「未定义占位符」→ 置 FAILED。

**后果**:6 个端到端调度测试全卡此(`KernelSchedulingTest`×3、`CrossCycleDependencyTest`×2、
`BackfillThrottleE2ETest`×1)。**更严重**:这不是测试问题——任何真实用户写 shell/SQL 任务
(任务即代码的核心场景),`${VAR}` 在生产同样会被误判 FAILED。

**根因**:`${...}` 语法在平台占位符与 shell/SQL 之间存在不可消解的歧义;
「未定义即抛错」的 typo 检测与「任务内容本就是脚本」根本冲突。

## 2. 决策(已与产品确认)

平台占位符改用**与 shell/SQL 不冲突的独立语法 `{{...}}`(双花括号,mustache/Jinja 惯例)**。
迁移后解析器**只处理 `{{...}}`,完全不碰任何 `${...}` 与裸 `$word`**——后者一律原样留给执行 shell。

- 保留 typo 检测:`{{unknown}}` 仍抛 `UnresolvedPlaceholderException`(`{{}}` 无歧义,误报不可能)。
- shell/SQL 彻底兼容:`${VAR}`、`${VAR:-default}`、`$(cmd)`、`$HOME`、`$$` 全部原样透传。

### 占位符语义(语法外壳从 `${}`/`$word` 改为 `{{}}`,**语义不变**)
| 旧语法 | 新语法 |
|---|---|
| `${yyyymmdd}` `${yyyy-mm-dd}` `${yyyymm}` `${yyyy}` | `{{yyyymmdd}}` `{{yyyy-mm-dd}}` `{{yyyymm}}` `{{yyyy}}` |
| `${yyyymmdd-1}` `${yyyymmdd-7*1}` `${yyyymmdd+2}` | `{{yyyymmdd-1}}` `{{yyyymmdd-7*1}}` `{{yyyymmdd+2}}` |
| `$bizdate` `$bizmonth` `$gmtdate` `$jobid` `$nodeid` `$taskid` | `{{bizdate}}` `{{bizmonth}}` `{{gmtdate}}` `{{jobid}}` `{{nodeid}}` `{{taskid}}` |
| `${customParam}`(paramsJson 自定义参数递归展开) | `{{customParam}}` |

> 裸 `$word` 内置词**取消**(`$bizdate` 等不再特殊处理,留给 shell);统一走 `{{bizdate}}`。

## 3. 功能需求(FR)

- **FR-001** 解析器 `resolve()` 只识别 `{{...}}`;扫描到 `${`、`$(`、裸 `$word`、`$$` 等一律原样输出,不解析、不抛错。
- **FR-002** `{{<fmt>}}` 日期格式化 + `{{<fmt>±N}}` 整数偏移,语义同旧实现(y/m/d token、偏移单位、`*N` 周倍数)。
- **FR-003** `{{bizdate|bizmonth|gmtdate|jobid|nodeid|taskid}}` 内置参数,语义同旧 `$word` 内置。
- **FR-004** `{{name}}` 自定义参数递归展开(paramsJson),含循环检测,语义同旧。
- **FR-005** `{{}}` 内非法/未定义:空 `{{}}`、未闭合 `{{x`、嵌套 `{{{{x}}}}`、未定义 `{{nope}}`、
  非法偏移 `{{yyyymmdd-}}`/`{{yyyymmdd-1x}}`、时间 token `{{hh24:mi:ss}}` —— 仍抛 `UnresolvedPlaceholderException`,
  沿用现有 `schedule.placeholder.*` / `schedule.offset.*` error code(i18n 不变)。
- **FR-006** `hasPlatformPlaceholder(content)` 改为检测 `{{`(替代旧 `\$\{`/`$word`);无 `{{` 走快速路径原样返回。
- **FR-007** 调用点(`SchedulerKernel`/`OpsService`/`TaskController` 预览)签名与 catch 逻辑不变,仅解析器内部语法变更。
- **FR-008** 现有 `${bizdate:-...}` 的 bash 临时放过补丁(本会话 `BASH_PARAM_EXPANSION`)随重写**移除**(被 FR-001 全覆盖)。

## 4. 迁移面(实现清单)

1. **解析器**: `ScheduleParamResolver.java` 重写扫描核心(`resolveText`/`hasPlatformPlaceholder`/`PLATFORM_PLACEHOLDER`);
   移除 `$word` 内置分支与 `BASH_PARAM_EXPANSION` 临时补丁;`resolveExpr`/`tryDateExpr`/`format`/`builtIn`/参数解析逻辑复用。
2. **解析器单测**: `ScheduleParamResolverTest.java` 全部 `${...}`/`$bizdate` → `{{...}}`;
   新增断言:`${VAR}`、`${VAR:-d}`、`$(cmd)`、`$HOME`、裸 `$bizdate` **全部原样返回**(shell 安全证伪)。
3. **前端**: `task-editor-pane.tsx`、`task-config-panel.tsx`、`workflow-canvas-view.tsx` 及
   `messages/{zh-CN,en-US}.json` 中占位符**提示/示例/帮助文案** `${...}`/`$bizdate` → `{{...}}`(两 bundle key 集一致)。
4. **文档**: `docs/scheduling-parameters.md`、`docs/architecture.md` 占位符语法小节更新为 `{{}}`;
   `ScheduleParamResolver` javadoc 同步。
5. **种子**: `data.sql` **无需改**(已确认 0 个平台占位符,全是 shell `${}`)——正是本特性要放过的内容。

## 5. 验收标准(SC)

- **SC-001** 6 个 E2E(`KernelSchedulingTest`/`CrossCycleDependencyTest`/`BackfillThrottleE2ETest`)全绿:
  种子 shell 脚本里的 `${BIZ_DATE}`/`${TABLE}`/`${bizdate:-...}` 等被原样透传,实例跑到 SUCCESS。
- **SC-002** `ScheduleParamResolverTest` 全绿(迁移后 + 新增 shell-passthrough 证伪用例),`Skipped: 0`。
- **SC-003** `cd backend && ./mvnw -pl dataweave-api -am test -Dmaven.build.cache.enabled=false` **api 全套件绿、`Skipped: 0`**
  (与本会话已修的 schema/`@Modifying`/隔离共同构成 main 绿基线)。
- **SC-004** 前端 `pnpm typecheck` + i18n key 集一致 CI 通过;预览端点对 `{{nope}}` 仍返回占位符错误。

## 6. 不在范围

- 不改占位符**语义**(日期/偏移/内置/自定义/循环检测/error code 全沿用)。
- 不引入 `{{}}` 之外的新能力(如 `{{var:-default}}` 平台级默认值——默认值交 shell)。
- 不动 schema/`@Modifying`/隔离修复(本会话已单独完成,见 commit 计划)。

## 7. 风险与缓解

- **R1 漏迁移导致 `{{}}` 与旧 `${}` 并存**:种子已确认无平台占位符;前端/文档为文案,逐处替换 + 全仓 grep 收口。
- **R2 解析器重写引入回归**:语义复用旧实现 + 单测全覆盖迁移 + E2E 兜底;证伪式新增 shell-passthrough 断言。
- **R3 跨特性**:与 011-weft-cleanup(删 AI 死码)无文件重叠;与本会话 schema 修复无冲突。
