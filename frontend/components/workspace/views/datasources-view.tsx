"use client"

import { useCallback, useEffect, useMemo, useState } from "react"
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  Database01Icon,
  Add01Icon,
  ConnectIcon,
  Edit02Icon,
  Delete02Icon,
  CheckmarkCircle01Icon,
  Cancel01Icon,
  Upload04Icon,
} from "@hugeicons/core-free-icons"
import { toast } from "sonner"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogFooter,
} from "@/components/ui/dialog"
import { Badge } from "@/components/ui/badge"
import { DropdownSelect } from "@/components/ui/select"
import { DataTable } from "@/components/ui/data-table"
import { type ColumnDef, type FilterDef, type FilterPreset, type FetchQuery, toQueryParams } from "@/lib/data-table"
import {
  listDatasourceTypes,
  createDatasource,
  updateDatasource,
  deleteDatasource,
  testDatasource,
  testDatasourceConfig,
  listDriverJars,
  uploadDriverJar,
  fetchDatasources,
} from "@/lib/datasource-api"
import type {
  DatasourceVO,
  DatasourceType,
  DriverJarVO,
  ConnectionTestResult,
  DatasourceCreateRequest,
} from "@/lib/types"
import { DATASOURCE_TYPE_CONFIG, CATEGORY_ORDER, buildJdbcUrl } from "@/lib/datasource-type-config"
import { useFormatDateTime } from "@/hooks/use-format-date-time"
import { useProjectContext, currentProjectId } from "@/lib/project-context"

export function DatasourcesView() {
  const t = useTranslations("datasources")
  const formatDateTime = useFormatDateTime()
  // 当前项目：切换后 fetcher 标识变化触发 DataTable 重取（FR-014/SC-007）
  const projectId = useProjectContext((s) => s.currentProjectId) ?? 1
  const [types, setTypes] = useState<DatasourceType[]>([])
  const [dialogOpen, setDialogOpen] = useState(false)
  const [editingDs, setEditingDs] = useState<DatasourceVO | null>(null)
  const [deleteConfirm, setDeleteConfirm] = useState<DatasourceVO | null>(null)
  const [testingId, setTestingId] = useState<number | null>(null)
  const [reloadKey, setReloadKey] = useState(0)

  useEffect(() => {
    listDatasourceTypes().then(setTypes).catch(() => setTypes([]))
  }, [])

  const reload = useCallback(() => setReloadKey((n) => n + 1), [])

  const typeName = (code: string) => types.find((t) => t.code === code)?.name ?? code

  const handleTestConnection = async (ds: DatasourceVO) => {
    setTestingId(ds.id)
    try {
      const result = await testDatasource(ds.id)
      if (result.success) {
        const detail = result.latencyMs > 0 ? `${result.latencyMs}ms` : ""
        const version = result.serverVersion ? ` · ${result.serverVersion}` : ""
        toast.success(`${result.message}${detail ? ` (${detail})` : ""}${version}`)
      } else {
        toast.error(result.message)
      }
      reload()
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Test failed")
    } finally {
      setTestingId(null)
    }
  }

  const handleDelete = async () => {
    if (!deleteConfirm) return
    try {
      await deleteDatasource(deleteConfirm.id)
      setDeleteConfirm(null)
      reload()
    } catch {
      // error handled
    }
  }

  const handleSave = async (req: DatasourceCreateRequest) => {
    try {
      if (editingDs) {
        await updateDatasource(editingDs.id, req)
      } else {
        await createDatasource(req)
      }
      setDialogOpen(false)
      reload()
    } catch {
      throw new Error("Save failed")
    }
  }

  // Type options for multiSelect filter
  const typeOptions = useMemo(() => {
    return types.map((dt) => ({ value: dt.code, label: dt.name }))
  }, [types])

  // Columns
  const columns = useMemo<ColumnDef<DatasourceVO>[]>(() => [
    { key: "name", header: t("col.name"), widthPct: 16 },
    {
      key: "typeCode",
      header: t("col.type"),
      widthPct: 12,
      cell: (row) => (
        <Badge variant="outline" className="text-xs">{typeName(row.typeCode)}</Badge>
      ),
    },
    {
      key: "host",
      header: t("col.host"),
      widthPct: 14,
      cell: (row) => (
        <span className="text-muted-foreground">
          {row.host ?? "—"}{row.port ? `:${row.port}` : ""}
        </span>
      ),
    },
    {
      key: "databaseName",
      header: t("col.database"),
      widthPct: 10,
      cell: (row) => <span className="text-muted-foreground">{row.databaseName ?? "—"}</span>,
    },
    {
      key: "connectionStatus",
      header: t("col.status"),
      widthPct: 10,
      cell: (row) => (
        <Badge
          variant={
            row.connectionStatus === "CONNECTED"
              ? "default"
              : row.connectionStatus === "DISCONNECTED"
                ? "destructive"
                : "secondary"
          }
          className="text-xs"
        >
          {row.connectionStatus === "CONNECTED"
            ? t("status.connected")
            : row.connectionStatus === "DISCONNECTED"
              ? t("status.disconnected")
              : t("status.untested")}
        </Badge>
      ),
    },
    {
      key: "createdAt",
      header: t("col.createdAt"),
      widthPct: 14,
      cell: (row) => (
        <span className="text-muted-foreground text-xs">{formatDateTime(row.createdAt)}</span>
      ),
    },
    {
      key: "actions",
      header: t("col.actions"),
      widthPct: 24,
      align: "right",
      cell: (row) => (
        <div className="flex justify-end gap-0.5">
          <Button variant="ghost" size="icon" className="size-7" onClick={() => { setEditingDs(row); setDialogOpen(true) }}>
            <HugeiconsIcon icon={Edit02Icon} className="size-3.5" />
          </Button>
          <Button
            variant="ghost"
            size="icon"
            className="size-7"
            title={t("actions.testConnection")}
            disabled={testingId !== null}
            onClick={() => handleTestConnection(row)}
          >
            <HugeiconsIcon
              icon={ConnectIcon}
              className={`size-3.5 ${testingId === row.id ? "animate-spin" : ""}`}
            />
          </Button>
          <Button variant="ghost" size="icon" className="size-7" onClick={() => setDeleteConfirm(row)}>
            <HugeiconsIcon icon={Delete02Icon} className="size-3.5 text-destructive" />
          </Button>
        </div>
      ),
    },
  ], [t, typeName, formatDateTime, testingId])

  // Filters
  const filters = useMemo<FilterDef[]>(() => [
    {
      key: "search",
      label: t("filterSearch"),
      kind: "search",
      placeholder: t("searchPlaceholder"),
      width: "w-48",
    },
    {
      key: "typeCode",
      label: t("filterType"),
      kind: "multiSelect",
      options: typeOptions,
      width: "w-36",
    },
    {
      key: "connectionStatus",
      label: t("filterStatus"),
      kind: "segmented",
      options: [
        { value: "CONNECTED", label: t("status.connected") },
        { value: "DISCONNECTED", label: t("status.disconnected") },
        { value: "UNKNOWN", label: t("status.untested") },
      ],
    },
  ], [t, typeOptions])

  // Presets
  const presets = useMemo<FilterPreset[]>(() => [
    { key: "disconnected", label: t("presetDisconnected"), set: { connectionStatus: "DISCONNECTED" } },
  ], [t])

  const fetcher = useCallback(
    (q: FetchQuery) => fetchDatasources(q, filters, projectId, toQueryParams),
    [filters, projectId],
  )

  // Types by category for dialog
  const typesByCategory = useMemo(() => {
    const map: Record<string, DatasourceType[]> = {}
    for (const cat of CATEGORY_ORDER) {
      map[cat] = types.filter((t) => t.category === cat)
    }
    return map
  }, [types])

  return (
    <div className="flex h-full flex-col gap-3 p-4">
      {/* DataTable */}
      <DataTable<DatasourceVO>
        columns={columns}
        getRowId={(r) => String(r.id)}
        mode="server"
        fetcher={fetcher}
        filters={filters}
        presets={presets}
        toolbarActions={
          <Button size="sm" onClick={() => { setEditingDs(null); setDialogOpen(true) }}>
            <HugeiconsIcon icon={Add01Icon} className="mr-1 size-4" />
            {t("newDatasource")}
          </Button>
        }
        emptyIcon={Database01Icon}
        emptyTitle={t("emptyTitle")}
        emptyHint={t("emptyHint")}
        reloadSignal={reloadKey}
      />

      {/* Create/Edit Dialog */}
      {dialogOpen && (
        <DatasourceDialog
          open={dialogOpen}
          onOpenChange={setDialogOpen}
          editing={editingDs}
          typesByCategory={typesByCategory}
          onSave={handleSave}
        />
      )}

      {/* Delete Confirmation */}
      {deleteConfirm && (
        <Dialog open onOpenChange={() => setDeleteConfirm(null)}>
          <DialogContent className="max-w-sm">
            <DialogHeader>
              <DialogTitle>{t("delete.title")}</DialogTitle>
            </DialogHeader>
            <p className="text-sm text-muted-foreground">
              {t("delete.confirm", { name: deleteConfirm.name })}
            </p>
            <DialogFooter>
              <Button variant="outline" onClick={() => setDeleteConfirm(null)}>
                {t("cancel")}
              </Button>
              <Button variant="destructive" onClick={handleDelete}>
                {t("delete.confirmBtn")}
              </Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
      )}
    </div>
  )
}

// ─── Create/Edit Dialog ──────────────────────────────────────

interface DialogProps {
  open: boolean
  onOpenChange: (v: boolean) => void
  editing: DatasourceVO | null
  typesByCategory: Record<string, DatasourceType[]>
  onSave: (req: DatasourceCreateRequest) => Promise<void>
}

function DatasourceDialog({ open, onOpenChange, editing, typesByCategory, onSave }: DialogProps) {
  const t = useTranslations("datasources")
  const [selectedType, setSelectedType] = useState(editing?.typeCode ?? "")
  const [name, setName] = useState(editing?.name ?? "")
  const [description, setDescription] = useState(editing?.description ?? "")
  const [fields, setFields] = useState<Record<string, string>>({})
  const [testing, setTesting] = useState(false)
  const [testResult, setTestResult] = useState<ConnectionTestResult | null>(null)
  const [driverJars, setDriverJars] = useState<DriverJarVO[]>([])
  const [driverJarId, setDriverJarId] = useState<number | null>(editing?.driverJarId ?? null)
  const [uploading, setUploading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState("")

  // Initialize fields from editing datasource
  useEffect(() => {
    if (editing) {
      setFields({
        host: editing.host ?? "",
        port: editing.port != null ? String(editing.port) : "",
        databaseName: editing.databaseName ?? "",
        username: editing.username ?? "",
        password: "",
      })
    }
  }, [editing])

  // 选类型变化时加载该类型的已上传驱动 jar 资产
  useEffect(() => {
    if (!selectedType) {
      setDriverJars([])
      return
    }
    listDriverJars(selectedType)
      .then(setDriverJars)
      .catch(() => setDriverJars([]))
  }, [selectedType])

  const config = selectedType ? DATASOURCE_TYPE_CONFIG[selectedType] : null

  const updateField = (key: string, value: string) => {
    setFields((prev) => ({ ...prev, [key]: value }))
    setTestResult(null)
  }

  const handleUpload = async (file: File) => {
    if (!selectedType) return
    setUploading(true)
    try {
      const vo = await uploadDriverJar(selectedType, file)
      // 去重后置顶（后端按 sha256 去重，可能返回既有资产）
      setDriverJars((prev) => [vo, ...prev.filter((d) => d.id !== vo.id)])
      setDriverJarId(vo.id)
      setTestResult(null)
      toast.success(t("form.driverUploaded"))
    } catch (e) {
      toast.error(e instanceof Error ? e.message : t("form.uploadFailed"))
    } finally {
      setUploading(false)
    }
  }

  const handleTest = async () => {
    setTesting(true)
    setTestResult(null)
    try {
      const req: DatasourceCreateRequest = {
        name,
        typeCode: selectedType,
        host: fields.host || null,
        port: fields.port ? Number(fields.port) : null,
        databaseName: fields.databaseName || null,
        username: fields.username || null,
        // 密码：用户输入了新密码则传明文；否则传 null，后端编辑模式下会用已保存密码
        password: fields.password || null,
        jdbcUrl: config?.jdbcUrlTemplate ? buildJdbcUrl(selectedType, fields) : null,
      }
      // 始终用当前表单值测试；编辑时传 datasourceId 让后端复用已保存密码
      const result = await testDatasourceConfig(req, editing?.id)
      setTestResult(result)
    } catch {
      setTestResult({ success: false, message: "Test failed", latencyMs: 0, serverVersion: null })
    } finally {
      setTesting(false)
    }
  }

  const handleSave = async () => {
    setSaving(true)
    setError("")
    try {
      const req: DatasourceCreateRequest = {
        name,
        typeCode: selectedType,
        projectId: currentProjectId(),
        host: fields.host || null,
        port: fields.port ? Number(fields.port) : null,
        databaseName: fields.databaseName || null,
        username: fields.username || null,
        password: fields.password || null,
        jdbcUrl: config?.jdbcUrlTemplate ? buildJdbcUrl(selectedType, fields) : null,
        description: description || null,
        driverJarId,
      }
      await onSave(req)
    } catch (e) {
      setError(e instanceof Error ? e.message : "Save failed")
    } finally {
      setSaving(false)
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>
            {editing ? t("dialog.editTitle") : t("dialog.newTitle")}
          </DialogTitle>
        </DialogHeader>

        <div className="grid gap-3 py-2">
          {/* Name */}
          <div className="grid gap-1">
            <label className="text-sm font-medium">{t("form.name")}</label>
            <Input value={name} onChange={(e) => setName(e.target.value)} placeholder={t("form.namePh")} />
          </div>

          {/* Type selector (disabled when editing) */}
          <div className="grid gap-1">
            <label className="text-sm font-medium">{t("form.type")}</label>
            {editing ? (
              <Input value={selectedType} disabled />
            ) : (
              <DropdownSelect
                value={selectedType}
                onChange={(v) => {
                  setSelectedType(v)
                  // Initialize fields with default values from config (e.g. MySQL → 3306)
                  const cfg = DATASOURCE_TYPE_CONFIG[v]
                  if (cfg) {
                    const defaults: Record<string, string> = {}
                    for (const f of cfg.fields) {
                      if (f.defaultValue != null) {
                        defaults[f.key] = String(f.defaultValue)
                      }
                    }
                    setFields(defaults)
                  } else {
                    setFields({})
                  }
                  setTestResult(null)
                }}
                placeholder={t("form.typePh")}
                triggerClassName="h-9"
                disableClear
                groups={CATEGORY_ORDER.map((cat) => ({
                  value: cat,
                  label: t(`category.${cat}`),
                }))}
                options={CATEGORY_ORDER.flatMap((cat) =>
                  (typesByCategory[cat] ?? []).map((dt) => ({
                    value: dt.code,
                    label: dt.name,
                    group: cat,
                  })),
                )}
              />
            )}
          </div>

          {/* Description */}
          <div className="grid gap-1">
            <label className="text-sm font-medium">{t("form.description")}</label>
            <Input value={description} onChange={(e) => setDescription(e.target.value)} placeholder={t("form.descPh")} />
          </div>

          {/* Dynamic fields based on type */}
          {config?.fields.map((f) => (
            <div key={f.key} className="grid gap-1">
              <label className="text-sm font-medium">
                {t(`form.${f.labelKey}`)}
                {f.required && <span className="ml-0.5 text-destructive">*</span>}
              </label>
              <Input
                type={f.type === "password" ? "password" : f.type === "number" ? "number" : "text"}
                value={fields[f.key] ?? (f.defaultValue != null ? String(f.defaultValue) : "")}
                onChange={(e) => updateField(f.key, e.target.value)}
                placeholder={f.placeholder}
              />
            </div>
          ))}

          {/* 驱动 jar（可选，覆盖内置默认驱动；应对老数据库版本兼容） */}
          <div className="grid gap-1">
            <label className="text-sm font-medium">{t("form.driverJar")}</label>
            <div className="flex flex-wrap items-center gap-2">
              <Badge variant={driverJarId ? "default" : "outline"} className="text-xs">
                {driverJarId ? t("form.driverSourceUploaded") : t("form.driverSourceBuiltin")}
              </Badge>
              <label
                className={`inline-flex cursor-pointer items-center gap-1 rounded-md border px-2 py-1 text-xs ${
                  uploading || !selectedType ? "pointer-events-none opacity-60" : "hover:bg-muted"
                }`}
              >
                <HugeiconsIcon icon={Upload04Icon} className="size-3.5" />
                <span>{uploading ? t("form.uploading") : t("form.upload")}</span>
                <input
                  type="file"
                  accept=".jar"
                  className="hidden"
                  onChange={(e) => {
                    const f = e.target.files?.[0]
                    if (f) handleUpload(f)
                    e.target.value = ""
                  }}
                />
              </label>
              {driverJarId && (
                <Button
                  size="sm"
                  variant="ghost"
                  className="h-7 px-2 text-xs"
                  onClick={() => {
                    setDriverJarId(null)
                    setTestResult(null)
                  }}
                >
                  {t("form.unbind")}
                </Button>
              )}
            </div>
            {driverJarId &&
              (() => {
                const jar = driverJars.find((d) => d.id === driverJarId)
                return jar ? (
                  <p className="text-xs text-muted-foreground">
                    {jar.driverClass ?? jar.originalName} · {jar.sha256Short}
                  </p>
                ) : null
              })()}
          </div>

          {/* Test result */}
          {testResult && (
            <div className={`flex items-center gap-2 rounded px-3 py-2 text-sm ${testResult.success ? "bg-success/10 text-success" : "bg-destructive/10 text-destructive"}`}>
              <HugeiconsIcon icon={testResult.success ? CheckmarkCircle01Icon : Cancel01Icon} className="size-4" />
              <span>{testResult.message}</span>
              {testResult.success && testResult.latencyMs > 0 && (
                <span className="text-xs opacity-70">({testResult.latencyMs}ms)</span>
              )}
            </div>
          )}

          {error && <p className="text-sm text-destructive">{error}</p>}
        </div>

        <DialogFooter className="gap-2">
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            {t("cancel")}
          </Button>
          <Button variant="outline" onClick={handleTest} disabled={testing || !selectedType}>
            <HugeiconsIcon icon={ConnectIcon} className="mr-1 size-4" />
            {testing ? t("form.testing") : t("form.testConnection")}
          </Button>
          <Button onClick={handleSave} disabled={saving || !name || !selectedType}>
            {saving ? t("form.saving") : t("form.save")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
