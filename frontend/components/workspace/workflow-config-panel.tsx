"use client"

/**
 * 工作流画布右侧栏「配置」tab：名称/描述/调度类型/cron 表达式/优先级 + 跨周期依赖 CRUD。
 *
 * 跨周期依赖（6.5）：自依赖为主（nodeKey == dependNodeKey、dependWorkflowId 同本流）——节点依赖自己上一周期 SUCCESS。
 * 新增表单选本工作流节点 + dateOffset（LAST_DAY=上一周期 / CURRENT_DAY=当天）+ earliest（首周期豁免截止日，可空=不启用）。
 */
import { useCallback, useEffect, useMemo, useState } from "react"
import { useLocale, useTranslations } from "next-intl"
import { zhCN, enUS } from "date-fns/locale"
import { Input } from "@/components/ui/input"
import { Button } from "@/components/ui/button"
import { DropdownSelect } from "@/components/ui/select"
import { DatePicker } from "@/components/ui/date-picker"
import { DwScroll } from "@/components/ui/dw-scroll"
import { LoadingState } from "@/components/workspace/shared/loading-state"
import {
  API_BASE,
  authFetch,
  type ApiResponse,
  type DagNode,
  type WorkflowDependency,
} from "@/lib/types"

function scheduleTypeLabel(v: string, t: ReturnType<typeof useTranslations>): string {
  switch (v) {
    case "MANUAL": return t("workflowConfig.scheduleManual")
    case "CRON": return t("workflowConfig.scheduleCron")
    case "DEPENDENCY": return t("workflowConfig.scheduleDependency")
    default: return v
  }
}

function priorityLabel(v: string, t: ReturnType<typeof useTranslations>): string {
  return t("workflowConfig.priorityLevel", { level: v })
}

function dateOffsetLabel(v: string, t: ReturnType<typeof useTranslations>): string {
  switch (v) {
    case "LAST_DAY": return t("workflowConfig.dateOffsetLastDay")
    case "CURRENT_DAY": return t("workflowConfig.dateOffsetCurrentDay")
    default: return v
  }
}

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
  workflowId: number
  nodes: DagNode[]
}

export function WorkflowConfigPanel({
  name, setName,
  description, setDescription,
  scheduleType, setScheduleType,
  cron, setCron,
  priority, setPriority,
  onDirty,
  workflowId,
  nodes,
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
          options={[
            { value: "MANUAL", label: scheduleTypeLabel("MANUAL", t) },
            { value: "CRON", label: scheduleTypeLabel("CRON", t) },
            { value: "DEPENDENCY", label: scheduleTypeLabel("DEPENDENCY", t) },
          ]}
          disableClear
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
          options={Array.from({ length: 10 }, (_, i) => ({ value: String(i), label: priorityLabel(String(i), t) }))}
          disableClear
        />
      </div>

      {/* 跨周期依赖（6.5） */}
      <CrossCycleDepsEditor workflowId={workflowId} nodes={nodes} />
    </div>
  )
}

/** 跨周期依赖编辑器：列表 + 新增（自依赖）+ 删除，调 /api/workflows/{id}/dependencies。 */
function CrossCycleDepsEditor({ workflowId, nodes }: { workflowId: number; nodes: DagNode[] }) {
  const t = useTranslations()
  const locale = useLocale()
  const dateLocale = useMemo(() => (locale === "zh-CN" ? zhCN : enUS), [locale])
  const [deps, setDeps] = useState<WorkflowDependency[]>([])
  const [loading, setLoading] = useState(true)
  const [nodeKey, setNodeKey] = useState("")
  const [dateOffset, setDateOffset] = useState("LAST_DAY")
  const [earliest, setEarliest] = useState("")

  const load = useCallback(() => {
    setLoading(true)
    authFetch(`${API_BASE}/api/workflows/${workflowId}/dependencies`, { cache: "no-store" })
      .then((r) => r.json() as Promise<ApiResponse<WorkflowDependency[]>>)
      .then((j) => { if (j.code === 0) setDeps(j.data ?? []) })
      .catch(() => { /* 静默 */ })
      .finally(() => setLoading(false))
  }, [workflowId])

  useEffect(() => { load() }, [load])

  const add = useCallback(async () => {
    if (!nodeKey) return
    const res = await authFetch(`${API_BASE}/api/workflows/${workflowId}/dependencies`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      // 自依赖：dependNodeKey == nodeKey、dependWorkflowId 同本流
      body: JSON.stringify({
        nodeKey,
        dependWorkflowId: workflowId,
        dependNodeKey: nodeKey,
        dateOffset,
        earliestBizDate: earliest || null,
        enabled: 1,
      }),
    })
    const j = (await res.json()) as ApiResponse<WorkflowDependency>
    if (j.code === 0) {
      setNodeKey("")
      setEarliest("")
      load()
    }
  }, [workflowId, nodeKey, dateOffset, earliest, load])

  const remove = useCallback(async (depId: number) => {
    await authFetch(`${API_BASE}/api/workflows/${workflowId}/dependencies/${depId}`, { method: "DELETE" })
    load()
  }, [workflowId, load])

  const nodeOptions = nodes.map((n) => ({ value: n.nodeKey, label: n.name || n.nodeKey }))

  return (
    <div className="flex flex-col gap-1.5">
      <label className="text-xs font-medium text-muted-foreground">{t("workflowConfig.crossCycleDeps")}</label>
      {/* 列表 */}
      <DwScroll className="max-h-40">
        <div className="flex flex-col gap-1">
          {loading ? (
            <LoadingState active={loading} iconSize="size-3.5" textSize="text-xs" />
          ) : deps.length === 0 ? (
            <span className="text-xs text-muted-foreground">{t("workflowConfig.noCrossCycleDeps")}</span>
          ) : (
            deps.map((d) => (
              <div key={d.id} className="flex items-center gap-1 rounded border border-border px-2 py-1 text-xs">
                <span className="flex-1 truncate">
                  {d.nodeKey}
                  {d.dependNodeKey && d.dependNodeKey !== d.nodeKey ? `${t("workflowConfig.sepArrow")}${d.dependNodeKey}` : ""}
                  {t("workflowConfig.sepDot")}{d.dateOffset}
                  {d.earliestBizDate ? `${t("workflowConfig.sepDot")}${t("workflowConfig.depGte")}${d.earliestBizDate}` : ""}
                </span>
                <button
                  type="button"
                  className="shrink-0 text-destructive hover:underline"
                  onClick={() => d.id != null && remove(d.id)}
                >
                  {t("common.delete")}
                </button>
              </div>
            ))
          )}
        </div>
      </DwScroll>
      {/* 新增（自依赖） */}
      {nodes.length === 0 ? (
        <span className="text-xs text-muted-foreground">{t("workflowConfig.noNodesForDep")}</span>
      ) : (
        <>
          <label className="text-xs font-medium text-muted-foreground">{t("workflowConfig.selectNodeForDep")}</label>
          <DropdownSelect value={nodeKey} onChange={setNodeKey} options={nodeOptions} />
        </>
      )}
      <div className="flex flex-col gap-1.5">
        <DropdownSelect value={dateOffset} onChange={setDateOffset} options={[
          { value: "LAST_DAY", label: dateOffsetLabel("LAST_DAY", t) },
          { value: "CURRENT_DAY", label: dateOffsetLabel("CURRENT_DAY", t) },
        ]} disableClear />
        <div className="flex gap-1">
          <DatePicker
            value={earliest}
            onChange={setEarliest}
            placeholder={t("workflowConfig.bizDateFormat")}
            className="flex-1"
            locale={dateLocale}
          />
          <Button size="sm" onClick={add} disabled={!nodeKey}>{t("workflowConfig.addDep")}</Button>
        </div>
      </div>
    </div>
  )
}
