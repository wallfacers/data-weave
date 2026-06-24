"use client"

/**
 * 任务编辑右侧栏「配置」tab：名称/类型/优先级/描述/调度参数/替换预览/超时/重试。
 * 从 TaskEditorPane 提取，所有字段以受控 props 传入。
 */
import { useMemo } from "react"
import { useTranslations } from "next-intl"
import { Input } from "@/components/ui/input"
import { Button } from "@/components/ui/button"
import { DropdownSelect } from "@/components/ui/select"
import type { DatasourceVO } from "@/lib/types"

const TYPE_OPTIONS = [
  { value: "SQL", label: "SQL" },
  { value: "SHELL", label: "SHELL" },
]

const PRESET_PLACEHOLDER = "__preset__"
const DATASOURCE_NONE = ""

interface ParamRow {
  name: string
  expr: string
}

export interface TaskConfigPanelProps {
  name: string
  setName: (v: string) => void
  type: string
  setType: (v: string) => void
  priority: number
  setPriority: (v: number) => void
  description: string
  setDescription: (v: string) => void
  paramRows: ParamRow[]
  addParam: () => void
  updateParam: (idx: number, patch: Partial<ParamRow>) => void
  removeParam: (idx: number) => void
  previewBizDate: string
  setPreviewBizDate: (v: string) => void
  previewResult: string | null
  previewError: string | null
  previewing: boolean
  handlePreview: () => void
  content: string
  timeoutSec: number
  setTimeoutSec: (v: number) => void
  retryMax: number
  setRetryMax: (v: number) => void
  onDirty: () => void
  // 数据源绑定（仅 SQL 任务）
  datasourceId: number | null
  setDatasourceId: (v: number | null) => void
  targetDatasourceId: number | null
  setTargetDatasourceId: (v: number | null) => void
  datasources: DatasourceVO[]
}

export function TaskConfigPanel({
  name, setName,
  type, setType,
  priority, setPriority,
  description, setDescription,
  paramRows, addParam, updateParam, removeParam,
  previewBizDate, setPreviewBizDate,
  previewResult, previewError, previewing, handlePreview,
  content,
  timeoutSec, setTimeoutSec,
  retryMax, setRetryMax,
  onDirty,
  datasourceId, setDatasourceId,
  targetDatasourceId, setTargetDatasourceId,
  datasources,
}: TaskConfigPanelProps) {
  const t = useTranslations()

  // 数据源下拉选项：空值=未绑定 + 各数据源
  const datasourceOptions = useMemo(() => {
    const none = { value: DATASOURCE_NONE, label: t("taskEditor.datasourceNone") }
    const items = datasources.map((ds) => ({
      value: String(ds.id),
      label: `${ds.name} (${ds.typeCode})`,
    }))
    return [none, ...items]
  }, [datasources, t])

  const expressionPresets = useMemo(
    () => [
      { value: PRESET_PLACEHOLDER, label: t("taskEditor.presetInsert") },
      { value: "${yyyymmdd}", label: t("taskEditor.presetYmd") },
      { value: "${yyyymmdd-1}", label: t("taskEditor.presetYmdMinus1") },
      { value: "${yyyymmdd-7*1}", label: t("taskEditor.presetYmdMinus7") },
      { value: "${yyyy-mm-dd}", label: t("taskEditor.presetYmdDash") },
      { value: "${yyyymm}", label: t("taskEditor.presetYm") },
      { value: "${yyyymm-1}", label: t("taskEditor.presetYmMinus1") },
      { value: "${yyyy-1}", label: t("taskEditor.presetYMinus1") },
      { value: "$bizdate", label: t("taskEditor.presetBizdate") },
      { value: "$bizmonth", label: t("taskEditor.presetBizmonth") },
      { value: "$gmtdate", label: t("taskEditor.presetGmtdate") },
    ],
    [t],
  )

  return (
    <div className="flex flex-col gap-3 p-3">
      {/* 名称 */}
      <div className="flex flex-col gap-1.5">
        <label className="text-xs font-medium text-muted-foreground">{t("taskEditor.name")}</label>
        <Input
          className="h-8 text-sm"
          value={name}
          onChange={(e) => { setName(e.target.value); onDirty() }}
          placeholder={t("taskEditor.namePlaceholder")}
        />
      </div>

      {/* 类型 + 优先级 */}
      <div className="flex gap-3">
        <div className="flex flex-1 flex-col gap-1.5">
          <label className="text-xs font-medium text-muted-foreground">{t("taskEditor.type")}</label>
          <DropdownSelect value={type} onChange={(v) => { setType(v); onDirty() }} options={TYPE_OPTIONS} />
        </div>
        <div className="flex flex-1 flex-col gap-1.5">
          <label className="text-xs font-medium text-muted-foreground">{t("taskEditor.priority")}</label>
          <Input
            className="h-8 text-sm"
            type="number" min={0} max={9}
            value={priority}
            onChange={(e) => { setPriority(Number(e.target.value)); onDirty() }}
          />
        </div>
      </div>

      {/* 数据源绑定（仅 SQL 任务显示） */}
      {type === "SQL" && (
        <div className="flex gap-3">
          <div className="flex flex-1 flex-col gap-1.5">
            <label className="text-xs font-medium text-muted-foreground">{t("taskEditor.datasource")}</label>
            <DropdownSelect
              value={datasourceId != null ? String(datasourceId) : DATASOURCE_NONE}
              onChange={(v) => { setDatasourceId(v ? Number(v) : null); onDirty() }}
              options={datasourceOptions}
            />
          </div>
          <div className="flex flex-1 flex-col gap-1.5">
            <label className="text-xs font-medium text-muted-foreground">{t("taskEditor.targetDatasource")}</label>
            <DropdownSelect
              value={targetDatasourceId != null ? String(targetDatasourceId) : DATASOURCE_NONE}
              onChange={(v) => { setTargetDatasourceId(v ? Number(v) : null); onDirty() }}
              options={datasourceOptions}
            />
          </div>
        </div>
      )}

      {/* 描述 */}
      <div className="flex flex-col gap-1.5">
        <label className="text-xs font-medium text-muted-foreground">{t("taskEditor.description")}</label>
        <Input
          className="h-8 text-sm"
          value={description}
          onChange={(e) => { setDescription(e.target.value); onDirty() }}
          placeholder={t("taskEditor.descriptionPlaceholder")}
        />
      </div>

      {/* 调度参数 */}
      <div className="flex flex-col gap-1.5">
        <div className="flex items-center justify-between">
          <label className="text-xs font-medium text-muted-foreground">{t("taskEditor.params")}</label>
          <Button size="sm" variant="ghost" className="h-6 px-2 text-xs" onClick={addParam}>{t("taskEditor.addParam")}</Button>
        </div>
        <p className="text-[11px] leading-relaxed text-muted-foreground">
          {t.rich("taskEditor.paramsHint", {
            code: (chunks: React.ReactNode) => <code className="font-mono">{chunks}</code>,
          })}
        </p>
        {paramRows.length === 0 ? (
          <div className="rounded-md border border-dashed border-input px-3 py-2 text-xs text-muted-foreground">
            {t("taskEditor.noParams")}
          </div>
        ) : (
          <div className="flex flex-col gap-2">
            {paramRows.map((row, idx) => (
              <div key={idx} className="flex flex-col gap-1.5 rounded-md border border-input p-2">
                <div className="flex gap-1.5">
                  <Input
                    className="h-7 flex-1 text-xs font-mono"
                    value={row.name}
                    onChange={(e) => { updateParam(idx, { name: e.target.value }); onDirty() }}
                    placeholder={t("taskEditor.paramName")}
                  />
                  <Button size="sm" variant="ghost" className="h-7 px-2 text-xs text-muted-foreground" onClick={() => removeParam(idx)}>{t("common.delete")}</Button>
                </div>
                <Input
                  className="h-7 flex-1 text-xs font-mono"
                  value={row.expr}
                  onChange={(e) => { updateParam(idx, { expr: e.target.value }); onDirty() }}
                  placeholder={t("taskEditor.exprPlaceholder")}
                />
                <DropdownSelect
                  value={PRESET_PLACEHOLDER}
                  onChange={(v) => {
                    if (v !== PRESET_PLACEHOLDER) { updateParam(idx, { expr: v }); onDirty() }
                  }}
                  options={expressionPresets}
                />
              </div>
            ))}
          </div>
        )}
      </div>

      {/* 替换预览 */}
      <div className="flex flex-col gap-1.5">
        <label className="text-xs font-medium text-muted-foreground">{t("taskEditor.preview")}</label>
        <div className="flex gap-1.5">
          <Input
            className="h-7 flex-1 text-xs font-mono"
            value={previewBizDate}
            onChange={(e) => setPreviewBizDate(e.target.value)}
            placeholder={t("taskEditor.bizDatePlaceholder")}
          />
          <Button size="sm" variant="secondary" className="h-7" onClick={handlePreview} disabled={previewing || !content}>
            {t("taskEditor.previewBtn")}
          </Button>
        </div>
        {previewError && <p className="text-[11px] text-destructive">{previewError}</p>}
        {previewResult !== null && (
          <pre className="max-h-[140px] overflow-auto rounded-md border border-input bg-muted/30 p-2 font-mono text-[11px] whitespace-pre-wrap break-all">
            {previewResult}
          </pre>
        )}
      </div>

      {/* 超时 + 重试 */}
      <div className="flex gap-3">
        <div className="flex flex-1 flex-col gap-1.5">
          <label className="text-xs font-medium text-muted-foreground">{t("taskEditor.timeout")}</label>
          <Input
            className="h-8 text-sm"
            type="number"
            value={timeoutSec}
            onChange={(e) => { setTimeoutSec(Number(e.target.value)); onDirty() }}
          />
        </div>
        <div className="flex flex-1 flex-col gap-1.5">
          <label className="text-xs font-medium text-muted-foreground">{t("taskEditor.retryMax")}</label>
          <Input
            className="h-8 text-sm"
            type="number" min={0}
            value={retryMax}
            onChange={(e) => { setRetryMax(Number(e.target.value)); onDirty() }}
          />
        </div>
      </div>
    </div>
  )
}
