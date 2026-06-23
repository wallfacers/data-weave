"use client"

/**
 * 工作流画布右侧栏「配置」tab：名称/描述/调度类型/cron 表达式/优先级。
 */
import { useTranslations } from "next-intl"
import { Input } from "@/components/ui/input"
import { DropdownSelect } from "@/components/ui/select"

const SCHEDULE_TYPE_OPTIONS = [
  { value: "MANUAL", label: "MANUAL" },
  { value: "CRON", label: "CRON" },
  { value: "DEPENDENCY", label: "DEPENDENCY" },
]

const PRIORITY_OPTIONS = [
  { value: "0", label: "0" }, { value: "1", label: "1" },
  { value: "2", label: "2" }, { value: "3", label: "3" },
  { value: "4", label: "4" }, { value: "5", label: "5" },
  { value: "6", label: "6" }, { value: "7", label: "7" },
  { value: "8", label: "8" }, { value: "9", label: "9" },
]

export interface WorkflowConfigPanelProps {
  name: string
  setName: (v: string) => void
  description: string
  setDescription: (v: string) => void
  scheduleType: string
  setScheduleType: (v: string) => void
  cron: string
  setCron: (v: string) => void
  priority: number
  setPriority: (v: number) => void
  onDirty: () => void
}

export function WorkflowConfigPanel({
  name, setName,
  description, setDescription,
  scheduleType, setScheduleType,
  cron, setCron,
  priority, setPriority,
  onDirty,
}: WorkflowConfigPanelProps) {
  const t = useTranslations()

  return (
    <div className="flex flex-col gap-3 p-3">
      {/* 名称 */}
      <div className="flex flex-col gap-1.5">
        <label className="text-xs font-medium text-muted-foreground">{t("workflowConfig.name")}</label>
        <Input
          className="h-8 text-sm"
          value={name}
          onChange={(e) => { setName(e.target.value); onDirty() }}
          placeholder={t("workflowConfig.namePlaceholder")}
        />
      </div>

      {/* 描述 */}
      <div className="flex flex-col gap-1.5">
        <label className="text-xs font-medium text-muted-foreground">{t("workflowConfig.description")}</label>
        <Input
          className="h-8 text-sm"
          value={description}
          onChange={(e) => { setDescription(e.target.value); onDirty() }}
          placeholder={t("workflowConfig.descriptionPlaceholder")}
        />
      </div>

      {/* 调度类型 */}
      <div className="flex flex-col gap-1.5">
        <label className="text-xs font-medium text-muted-foreground">{t("workflowConfig.scheduleType")}</label>
        <DropdownSelect
          value={scheduleType}
          onChange={(v) => { setScheduleType(v); onDirty() }}
          options={SCHEDULE_TYPE_OPTIONS}
        />
      </div>

      {/* cron 表达式（仅 CRON 时显示） */}
      {scheduleType === "CRON" && (
        <div className="flex flex-col gap-1.5">
          <label className="text-xs font-medium text-muted-foreground">{t("workflowConfig.cron")}</label>
          <Input
            className="h-8 text-sm font-mono"
            value={cron}
            onChange={(e) => { setCron(e.target.value); onDirty() }}
            placeholder={t("workflowConfig.cronPlaceholder")}
          />
        </div>
      )}

      {/* 优先级 */}
      <div className="flex flex-col gap-1.5">
        <label className="text-xs font-medium text-muted-foreground">{t("workflowConfig.priority")}</label>
        <DropdownSelect
          value={String(priority)}
          onChange={(v) => { setPriority(Number(v)); onDirty() }}
          options={PRIORITY_OPTIONS}
        />
      </div>
    </div>
  )
}
