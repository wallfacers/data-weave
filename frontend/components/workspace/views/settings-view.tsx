"use client"

import { useCallback, useEffect, useState } from "react"
import { useTranslations } from "next-intl"
import { useApi } from "@/lib/auth"
import type { ApiResponse } from "@/lib/types"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Badge } from "@/components/ui/badge"

/* ------------------------------------------------------------------ */
/*  Types                                                              */
/* ------------------------------------------------------------------ */

interface User {
  id: number
  tenantId: number
  username: string
  displayName: string
  email: string
  status: string
}

interface Role {
  id: number
  tenantId: number
  code: string
  name: string
  description: string
}

interface Project {
  id: number
  tenantId: number
  code: string
  name: string
  status: string
  ownerId: number
}

type Tab = "users" | "roles" | "projects"

/* ------------------------------------------------------------------ */
/*  Generic fetch + CRUD helpers                                       */
/* ------------------------------------------------------------------ */

function useCrud<T extends { id: number }>(basePath: string) {
  const api = useApi()
  const [items, setItems] = useState<T[]>([])
  const [loading, setLoading] = useState(true)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const res = await api(basePath)
      const json = (await res.json()) as ApiResponse<T[]>
      if (json.code === 0 && json.data) setItems(json.data)
    } catch {
      /* ignore */
    } finally {
      setLoading(false)
    }
  }, [api, basePath])

  useEffect(() => { void load() }, [load])

  async function create(body: Record<string, string>) {
    const res = await api(basePath, {
      method: "POST",
      body: JSON.stringify(body),
    })
    const json = (await res.json()) as ApiResponse<unknown>
    if (json.code === 0) await load()
    return res
  }

  async function update(id: number, body: Record<string, string>) {
    const res = await api(`${basePath}/${id}`, {
      method: "PUT",
      body: JSON.stringify(body),
    })
    const json = (await res.json()) as ApiResponse<unknown>
    if (json.code === 0) await load()
    return res
  }

  async function remove(id: number) {
    const res = await api(`${basePath}/${id}`, { method: "DELETE" })
    const json = (await res.json()) as ApiResponse<unknown>
    if (json.code === 0) await load()
    return res
  }

  return { items, loading, load, create, update, remove }
}

/* ------------------------------------------------------------------ */
/*  Simple inline form for create                                      */
/* ------------------------------------------------------------------ */

function InlineForm({
  fields,
  onSubmit,
  onCancel,
  submitLabel,
}: {
  fields: { key: string; label: string; type?: string; placeholder?: string }[]
  onSubmit: (vals: Record<string, string>) => void
  onCancel: () => void
  submitLabel: string
}) {
  const t = useTranslations("settingsView")
  const [vals, setVals] = useState<Record<string, string>>({})

  return (
    <div className="flex flex-wrap items-end gap-2 rounded-[var(--radius-lg)] bg-muted/50 p-3">
      {fields.map((f) => (
        <div key={f.key} className="flex min-w-[140px] flex-col gap-1">
          <label className="text-xs text-muted-foreground">{f.label}</label>
          <Input
            type={f.type ?? "text"}
            placeholder={f.placeholder ?? f.label}
            value={vals[f.key] ?? ""}
            onChange={(e) => setVals((v) => ({ ...v, [f.key]: e.target.value }))}
            className="h-8 bg-background text-sm"
          />
        </div>
      ))}
      <div className="flex gap-1.5">
        <Button
          size="sm"
          onClick={() => {
            if (fields.every((f) => vals[f.key]?.trim())) onSubmit(vals)
          }}
        >
          {submitLabel}
        </Button>
        <Button size="sm" variant="ghost" onClick={onCancel}>
          {t("cancel")}
        </Button>
      </div>
    </div>
  )
}

/* ------------------------------------------------------------------ */
/*  Users Tab                                                          */
/* ------------------------------------------------------------------ */

function UsersTab() {
  const t = useTranslations("settingsView")
  const { items, loading, create, update, remove } = useCrud<User>("/api/users")
  const [adding, setAdding] = useState(false)

  if (loading) return <p className="p-4 text-sm text-muted-foreground">{t("loading")}</p>

  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center justify-between">
        <h2 className="font-serif text-lg font-semibold">{t("usersTitle")}</h2>
        <Button size="sm" onClick={() => setAdding(true)}>
          {t("addUser")}
        </Button>
      </div>

      {adding && (
        <InlineForm
          fields={[
            { key: "username", label: t("fieldUsername"), placeholder: "username" },
            { key: "password", label: t("fieldPassword"), type: "password", placeholder: t("fieldPasswordPlaceholder") },
            { key: "displayName", label: t("fieldDisplayName"), placeholder: t("fieldDisplayName") },
            { key: "email", label: t("fieldEmail"), placeholder: "user@example.com" },
          ]}
          onSubmit={async (vals) => {
            await create(vals)
            setAdding(false)
          }}
          onCancel={() => setAdding(false)}
          submitLabel={t("submitCreate")}
        />
      )}

      <div className="overflow-x-auto rounded-[var(--radius-lg)] border">
        <table className="w-full text-sm">
          <thead>
            <tr className="bg-muted/50 text-left text-xs text-muted-foreground">
              <th className="px-3 py-2 font-medium">{t("colUsername")}</th>
              <th className="px-3 py-2 font-medium">{t("colDisplayName")}</th>
              <th className="px-3 py-2 font-medium">{t("colEmail")}</th>
              <th className="px-3 py-2 font-medium">{t("colStatus")}</th>
              <th className="px-3 py-2 font-medium text-right">{t("colActions")}</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border">
            {items.map((u) => (
              <tr key={u.id} className="hover:bg-muted/30">
                <td className="px-3 py-2 font-mono text-xs">{u.username}</td>
                <td className="px-3 py-2">{u.displayName}</td>
                <td className="px-3 py-2 text-muted-foreground">{u.email || "—"}</td>
                <td className="px-3 py-2">
                  <Badge variant={u.status === "ACTIVE" ? "success" : "destructive"}>
                    {u.status === "ACTIVE" ? t("statusActive") : t("statusDisabled")}
                  </Badge>
                </td>
                <td className="px-3 py-2 text-right">
                  <Button
                    size="xs"
                    variant="ghost"
                    onClick={async () => {
                      const newStatus = u.status === "ACTIVE" ? "DISABLED" : "ACTIVE"
                      await update(u.id, { status: newStatus })
                    }}
                  >
                    {u.status === "ACTIVE" ? t("disable") : t("enable")}
                  </Button>
                  <Button
                    size="xs"
                    variant="ghost"
                    onClick={() => void remove(u.id)}
                  >
                    {t("delete")}
                  </Button>
                </td>
              </tr>
            ))}
            {items.length === 0 && (
              <tr>
                <td colSpan={5} className="px-3 py-6 text-center text-muted-foreground">
                  {t("emptyUsers")}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}

/* ------------------------------------------------------------------ */
/*  Roles Tab                                                          */
/* ------------------------------------------------------------------ */

function RolesTab() {
  const t = useTranslations("settingsView")
  const { items, loading, create, remove } = useCrud<Role>("/api/roles")
  const [adding, setAdding] = useState(false)

  if (loading) return <p className="p-4 text-sm text-muted-foreground">{t("loading")}</p>

  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center justify-between">
        <h2 className="font-serif text-lg font-semibold">{t("rolesTitle")}</h2>
        <Button size="sm" onClick={() => setAdding(true)}>
          {t("addRole")}
        </Button>
      </div>

      {adding && (
        <InlineForm
          fields={[
            { key: "code", label: t("fieldRoleCode"), placeholder: "DEVELOPER" },
            { key: "name", label: t("fieldRoleName"), placeholder: t("fieldRoleNamePlaceholder") },
            { key: "description", label: t("fieldDescription"), placeholder: t("fieldRoleDescPlaceholder") },
          ]}
          onSubmit={async (vals) => {
            await create(vals)
            setAdding(false)
          }}
          onCancel={() => setAdding(false)}
          submitLabel={t("submitCreate")}
        />
      )}

      <div className="overflow-x-auto rounded-[var(--radius-lg)] border">
        <table className="w-full text-sm">
          <thead>
            <tr className="bg-muted/50 text-left text-xs text-muted-foreground">
              <th className="px-3 py-2 font-medium">{t("colCode")}</th>
              <th className="px-3 py-2 font-medium">{t("colName")}</th>
              <th className="px-3 py-2 font-medium">{t("colDescription")}</th>
              <th className="px-3 py-2 font-medium text-right">{t("colActions")}</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border">
            {items.map((r) => (
              <tr key={r.id} className="hover:bg-muted/30">
                <td className="px-3 py-2 font-mono text-xs">{r.code}</td>
                <td className="px-3 py-2">{r.name}</td>
                <td className="px-3 py-2 text-muted-foreground">
                  {r.description || "—"}
                </td>
                <td className="px-3 py-2 text-right">
                  <Button
                    size="xs"
                    variant="ghost"
                    onClick={() => void remove(r.id)}
                  >
                    {t("delete")}
                  </Button>
                </td>
              </tr>
            ))}
            {items.length === 0 && (
              <tr>
                <td colSpan={4} className="px-3 py-6 text-center text-muted-foreground">
                  {t("emptyRoles")}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}

/* ------------------------------------------------------------------ */
/*  Projects Tab                                                       */
/* ------------------------------------------------------------------ */

function ProjectsTab() {
  const t = useTranslations("settingsView")
  const { items, loading, create, remove } = useCrud<Project>("/api/projects")
  const [adding, setAdding] = useState(false)

  if (loading) return <p className="p-4 text-sm text-muted-foreground">{t("loading")}</p>

  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center justify-between">
        <h2 className="font-serif text-lg font-semibold">{t("projectsTitle")}</h2>
        <Button size="sm" onClick={() => setAdding(true)}>
          {t("addProject")}
        </Button>
      </div>

      {adding && (
        <InlineForm
          fields={[
            { key: "code", label: t("fieldProjectCode"), placeholder: "my-project" },
            { key: "name", label: t("fieldProjectName"), placeholder: t("fieldProjectNamePlaceholder") },
          ]}
          onSubmit={async (vals) => {
            await create(vals)
            setAdding(false)
          }}
          onCancel={() => setAdding(false)}
          submitLabel={t("submitCreate")}
        />
      )}

      <div className="overflow-x-auto rounded-[var(--radius-lg)] border">
        <table className="w-full text-sm">
          <thead>
            <tr className="bg-muted/50 text-left text-xs text-muted-foreground">
              <th className="px-3 py-2 font-medium">{t("colCode")}</th>
              <th className="px-3 py-2 font-medium">{t("colName")}</th>
              <th className="px-3 py-2 font-medium">{t("colStatus")}</th>
              <th className="px-3 py-2 font-medium text-right">{t("colActions")}</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border">
            {items.map((p) => (
              <tr key={p.id} className="hover:bg-muted/30">
                <td className="px-3 py-2 font-mono text-xs">{p.code}</td>
                <td className="px-3 py-2">{p.name}</td>
                <td className="px-3 py-2">
                  <Badge variant={p.status === "ACTIVE" ? "success" : "secondary"}>
                    {p.status === "ACTIVE" ? t("statusProjectActive") : p.status}
                  </Badge>
                </td>
                <td className="px-3 py-2 text-right">
                  <Button
                    size="xs"
                    variant="ghost"
                    onClick={() => void remove(p.id)}
                  >
                    {t("delete")}
                  </Button>
                </td>
              </tr>
            ))}
            {items.length === 0 && (
              <tr>
                <td colSpan={4} className="px-3 py-6 text-center text-muted-foreground">
                  {t("emptyProjects")}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}

/* ------------------------------------------------------------------ */
/*  Main Settings View                                                 */
/* ------------------------------------------------------------------ */

const TABS: { key: Tab; labelKey: "tabUsers" | "tabRoles" | "tabProjects" }[] = [
  { key: "users", labelKey: "tabUsers" },
  { key: "roles", labelKey: "tabRoles" },
  { key: "projects", labelKey: "tabProjects" },
]

export function SettingsView() {
  const t = useTranslations("settingsView")
  const [tab, setTab] = useState<Tab>("users")

  return (
    <div className="flex flex-1 flex-col gap-4 p-4">
      {/* Tab 条 */}
      <div className="flex gap-1">
        {TABS.map((tabItem) => (
          <Button
            key={tabItem.key}
            variant={tab === tabItem.key ? "secondary" : "ghost"}
            size="sm"
            onClick={() => setTab(tabItem.key)}
          >
            {t(tabItem.labelKey)}
          </Button>
        ))}
      </div>

      {/* 内容 */}
      <Card>
        <CardContent className="p-4">
          {tab === "users" && <UsersTab />}
          {tab === "roles" && <RolesTab />}
          {tab === "projects" && <ProjectsTab />}
        </CardContent>
      </Card>
    </div>
  )
}
