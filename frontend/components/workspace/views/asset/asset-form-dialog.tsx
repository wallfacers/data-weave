"use client"

/**
 * 资产编目 / 编辑 Dialog（029 US1）。
 * - 创建：全量 payload(qualifiedName 必填 + 数据源 Select);
 * - 编辑：`diffPatch` 只提交改动字段(FR-002 部分更新),qualifiedName/数据源不可改。
 * 提交经父级 onSubmit(返回是否关闭);三态/错误提示由父级 resolveGate 处理。
 */

import { useEffect, useState } from "react"
import { useTranslations } from "next-intl"
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogFooter,
  DialogTitle,
} from "@/components/ui/dialog"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { DropdownSelect } from "@/components/ui/select"
import { diffPatch } from "@/lib/asset-patch"
import type { AssetDetail, Sensitivity } from "@/lib/catalog-api"

const SENSITIVITIES: Sensitivity[] = ["PUBLIC", "INTERNAL", "CONFIDENTIAL", "PII"]

export interface AssetFormDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  mode: "create" | "edit"
  initial?: AssetDetail | null
  datasources: { id: number; name: string }[]
  /** 返回 true 关闭 dialog（已执行/待审批）;false 保持打开（失败）。 */
  onSubmit: (payload: Record<string, unknown>) => Promise<boolean>
}

interface FormState {
  datasourceId: string
  qualifiedName: string
  name: string
  description: string
  ownerId: string
  stewardId: string
  sensitivity: string
  tags: string
  lineageTableRef: string
}

const EMPTY: FormState = {
  datasourceId: "",
  qualifiedName: "",
  name: "",
  description: "",
  ownerId: "",
  stewardId: "",
  sensitivity: "INTERNAL",
  tags: "",
  lineageTableRef: "",
}

function fromDetail(d: AssetDetail): FormState {
  return {
    datasourceId: String(d.datasourceId),
    qualifiedName: d.qualifiedName,
    name: d.name ?? "",
    description: d.description ?? "",
    ownerId: d.ownerId != null ? String(d.ownerId) : "",
    stewardId: d.stewardId != null ? String(d.stewardId) : "",
    sensitivity: d.sensitivity,
    tags: (d.tags ?? []).join(", "),
    lineageTableRef: d.lineageTableRef ?? "",
  }
}

/** 表单态 → 规范化值（用于 diff 与提交）。 */
function normalize(f: FormState) {
  const num = (s: string) => (s.trim() === "" ? null : Number(s.trim()))
  return {
    name: f.name.trim(),
    description: f.description.trim(),
    ownerId: num(f.ownerId),
    stewardId: num(f.stewardId),
    sensitivity: f.sensitivity,
    tags: f.tags.split(",").map((t) => t.trim()).filter(Boolean),
    lineageTableRef: f.lineageTableRef.trim(),
  }
}

const EDITABLE = ["name", "description", "ownerId", "stewardId", "sensitivity", "tags", "lineageTableRef"] as const

export function AssetFormDialog({ open, onOpenChange, mode, initial, datasources, onSubmit }: AssetFormDialogProps) {
  const t = useTranslations("assetCatalog")
  const tc = useTranslations("common")
  const [form, setForm] = useState<FormState>(EMPTY)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!open) return
    setError(null)
    setBusy(false)
    setForm(mode === "edit" && initial ? fromDetail(initial) : EMPTY)
  }, [open, mode, initial])

  const set = (k: keyof FormState) => (v: string) => setForm((s) => ({ ...s, [k]: v }))

  const submit = async () => {
    if (mode === "create" && form.qualifiedName.trim() === "") {
      setError(t("errAssetInvalid"))
      return
    }
    if (mode === "create" && form.datasourceId === "") {
      setError(t("formDatasourceRequired"))
      return
    }
    setBusy(true)
    setError(null)
    let payload: Record<string, unknown>
    if (mode === "create") {
      payload = {
        datasourceId: Number(form.datasourceId),
        qualifiedName: form.qualifiedName.trim(),
        ...normalize(form),
      }
    } else {
      const initialNorm = normalize(fromDetail(initial!))
      const currentNorm = normalize(form)
      payload = diffPatch(initialNorm, currentNorm, [...EDITABLE])
    }
    try {
      const close = await onSubmit(payload)
      if (close) onOpenChange(false)
    } finally {
      setBusy(false)
    }
  }

  const dsOptions = datasources.map((d) => ({ value: String(d.id), label: `${d.name} (#${d.id})` }))
  const sensOptions = SENSITIVITIES.map((s) => ({ value: s, label: s }))

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>{mode === "create" ? t("createTitle") : t("editTitle")}</DialogTitle>
        </DialogHeader>

        <div className="flex flex-col gap-3">
          {mode === "create" ? (
            <>
              <Field label={t("datasource")}>
                <DropdownSelect
                  value={form.datasourceId}
                  onChange={set("datasourceId")}
                  options={dsOptions}
                  placeholder={t("datasourcePlaceholder")}
                  triggerClassName="h-9"
                />
              </Field>
              <Field label={t("qualifiedName")}>
                <Input value={form.qualifiedName} onChange={(e) => set("qualifiedName")(e.target.value)} placeholder="db.schema.table" />
              </Field>
            </>
          ) : (
            <div className="text-xs text-muted-foreground">{initial?.qualifiedName}</div>
          )}

          <Field label={t("name")}>
            <Input value={form.name} onChange={(e) => set("name")(e.target.value)} />
          </Field>
          <Field label={t("descriptionLabel")}>
            <Input value={form.description} onChange={(e) => set("description")(e.target.value)} />
          </Field>
          <div className="flex gap-3">
            <Field label={t("owner")} className="flex-1">
              <Input value={form.ownerId} onChange={(e) => set("ownerId")(e.target.value)} inputMode="numeric" placeholder="#" />
            </Field>
            <Field label={t("steward")} className="flex-1">
              <Input value={form.stewardId} onChange={(e) => set("stewardId")(e.target.value)} inputMode="numeric" placeholder="#" />
            </Field>
          </div>
          <Field label={t("sensitivity")}>
            <DropdownSelect value={form.sensitivity} onChange={set("sensitivity")} options={sensOptions} triggerClassName="h-9" disableClear />
          </Field>
          <Field label={t("tagsLabel")}>
            <Input value={form.tags} onChange={(e) => set("tags")(e.target.value)} placeholder={t("tagsPlaceholder")} />
          </Field>
          <Field label={t("lineageTableRef")}>
            <Input value={form.lineageTableRef} onChange={(e) => set("lineageTableRef")(e.target.value)} placeholder="db.table" />
          </Field>

          {error && <div className="text-sm text-destructive">{error}</div>}
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={busy}>
            {tc("cancel")}
          </Button>
          <Button onClick={submit} disabled={busy}>
            {busy ? t("submitting") : mode === "create" ? tc("create") : tc("save")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function Field({ label, children, className }: { label: string; children: React.ReactNode; className?: string }) {
  return (
    <label className={`flex flex-col gap-1 ${className ?? ""}`}>
      <span className="text-xs font-medium text-muted-foreground">{label}</span>
      {children}
    </label>
  )
}
