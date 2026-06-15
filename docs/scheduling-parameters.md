# 调度参数（Scheduling Parameters）

任务执行的 `content`（SQL / Shell）支持 `${...}` 占位符,在**下发执行前**替换为具体值。对齐阿里 DataWorks「调度参数」体系(本期实现业务日期一套,秒级 `$[...]` 留二期)。设计细节见 `openspec/changes/scheduling-parameters/design.md`,实现见 `ScheduleParamResolver`。

## 时间基准

替换基于实例的 **业务日期 `biz_date`**(T-1,数据发生日),在实例生成时冻结存入 `task_instance`,不随实际执行时刻(排队 / 重试延迟)变化。一个典型 T+1 任务:今天凌晨跑,处理昨天的数据。

## 语法

### 业务日期 `${<fmt>}`（基于 biz_date，天精度）

token 仅 `yyyy` / `mm` / `dd`;`hh` / `mi` / `ss` 不支持(秒级走二期 `$[...]`)。

| 写法 | 含义 | biz_date=2026-06-11 时 |
|------|------|------------------------|
| `${yyyymmdd}` | 业务日 8 位 | `20260611` |
| `${yyyy-mm-dd}` | 业务日带横线 | `2026-06-11` |
| `${yyyymm}` | 年月 | `202606` |
| `${yyyy}` | 年 | `2026` |
| `${yyyymmdd-1}` | 前一天 | `20260610` |
| `${yyyymm-1}` | 上月 | `202605` |
| `${yyyy-1}` | 去年 | `2025` |
| `${yyyymmdd-7*1}` | 上周(天偏移 ×7) | `20260604` |

偏移单位取 fmt 最小单位(`dd`→天、`mm`→月、`yyyy`→年);月末自动 clamp(2026-03-31 `${yyyymm-1}`→`202602`)。

### 系统内置参数

| 参数 | 含义 |
|------|------|
| `$bizdate` | 业务日 `yyyymmdd`(≡ `${yyyymmdd}`) |
| `$bizmonth` | 业务月 `yyyymm`(同月取上月,否则取业务日月份) |
| `$gmtdate` | 当前日期 `yyyymmdd` |
| `$jobid` | workflow 实例 id |
| `$nodeid` | workflow 节点 id |
| `$taskid` | 任务实例 id |

### 自定义参数（递归展开）

`paramsJson` 为 `{"name":"expr"}`。`content` 里 `${name}` 展开为 `expr`,**若 `expr` 仍含 `${...}` 继续递归展开**(任意深度,带循环检测):

```
paramsJson: {"biz_dt":"${yyyymmdd-1}", "biz_pt":"dt=${biz_dt}"}
content:    "WHERE ${biz_pt}"
展开:       WHERE dt=20260610
```

## 限制

- 字面嵌套 `${${...}}`(用展开结果当参数名)**不支持** → 解析失败。
- 未定义占位符(`${x}` 既非日期格式也非自定义参数)→ 实例 `FAILED`,`failure_reason` 命名占位符。
- 非内置 `$word`(如 shell `$HOME`)原样保留,不替换。
- 无 `$` 的 `content` 原样返回(no-op,存量任务零影响)。

## 替换时机

content 下发执行前,在 `SchedulerKernel.assign()` 构造 `DispatchCommand` 处替换(`resolveContentSafely`)。替换在认领事务内,失败被 catch 隔离(仅该实例 `FAILED`),不连坐同批次。进程内 / 分布式两条执行路径共用此替换点,worker 与执行器无感。

## 定时表达式

用 Spring 6 字段 cron(`秒 分 时 日 月 周`),支持秒级(强于 DataWorks 的分钟级)。

⚠️ DataWorks 用户习惯的 Quartz cron 扩展字符 **`?` / `L` / `W` / `#`** 不被 Spring 支持,直接粘贴会 parse 失败。例如 DataWorks 的 `0 0 2 * * ?` 在本平台需改为 `0 0 2 * * *`(`?`→具体字段或 `*`)。前端任务编辑器的 cron 输入有校验提示。
