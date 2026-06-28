/**
 * 业务日期默认值（T-1，`yyyy-MM-dd`）：与后端 `WorkflowTriggerService.defaultBizDate` 及
 * cron 触发的 `due.minusDays(1)` 同约定。运行任务/工作流不显式指定 bizDate 时用它兜底，
 * 保证脚本里的平台内置占位符 `{{bizdate}}`/`{{bizmonth}}` 有值（否则后端解析抛 `schedule.bizdate.empty`，
 * 节点在下发前即 FAILED、无执行日志）。
 */
export function yesterdayBizDate(): string {
  const d = new Date()
  d.setDate(d.getDate() - 1)
  return d.toISOString().slice(0, 10)
}
