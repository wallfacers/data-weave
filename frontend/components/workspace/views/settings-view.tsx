"use client"

import { useCallback, useEffect, useState } from "react"
import { useTranslations } from "next-intl"
import { useApi } from "@/lib/auth"
import type { ApiResponse } from "@/lib/types"
import { Card, CardContent } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Badge } from "@/components/ui/badge"
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"

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
/*  Dialog state machines                                              */
/* ------------------------------------------------------------------ */

type UserDialogState =
  | { mode: "closed" }
  | { mode: "create" }
  | { mode: "edit"; id: number }

type RoleDialogState =
  | { mode: "closed" }
  | { mode: "create" }
  | { mode: "edit"; id: number }

type ProjectDialogState =
  | { mode: "closed" }
  | { mode: "create" }
  | { mode: "edit"; id: number }

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
/*  Shared form-field helper                                           */
/* ------------------------------------------------------------------ */

function FormField({
  label,
  children,
}: {
  label: string
  children: React.ReactNode
}) {
  return (
    <div className="flex flex-col gap-1.5">
      <label className="text-xs text-muted-foreground">{label}</label>
      {children}
    </div>
  )
}

/* ------------------------------------------------------------------ */
/*  Users Tab                                                          */
/* ------------------------------------------------------------------ */

function UsersTab() {
  const t = useTranslations("settingsView")
  const { items, loading, create, update, remove } = useCrud<User>("/api/users")
  const [dialog, setDialog] = useState<UserDialogState>({ mode: "closed" })

  /* form fields — separate from dialog state so edits survive re-renders */
  const [username, setUsername] = useState("")
  const [password, setPassword] = useState("")
  const [displayName, setDisplayName] = useState("")
  const [email, setEmail] = useState("")

  const closeDialog = useCallback(() => {
    setDialog({ mode: "closed" })
    setUsername(""); setPassword(""); setDisplayName(""); setEmail("")
  }, [])

  const openCreate = () => {
    setUsername(""); setPassword(""); setDisplayName(""); setEmail("")
    setDialog({ mode: "create" })
  }

  const openEdit = (u: User) => {
    setUsername(u.username)
    setPassword("")
    setDisplayName(u.displayName)
    setEmail(u.email ?? "")
    setDialog({ mode: "edit", id: u.id })
  }

  const submit = async () => {
    if (dialog.mode === "create") {
      if (!username.trim() || !password.trim()) return
      await create({ username, password, displayName, email })
    } else if (dialog.mode === "edit") {
      const body: Record<string, string> = { username, displayName, email }
      if (password.trim()) body.password = password
      await update(dialog.id, body)
    }
    closeDialog()
  }

  if (loading) return <p className="p-4 text-sm text-muted-foreground">{t("loading")}</p>

  const isOpen = dialog.mode !== "closed"

  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center justify-between">
        <h2 className="font-serif text-lg font-semibold">{t("usersTitle")}</h2>
        <Button size="sm" onClick={openCreate}>
          {t("addUser")}
        </Button>
      </div>

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
                    onClick={() => openEdit(u)}
                  >
                    {t("edit")}
                  </Button>
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

      {/* ---- Dialog ---- */}
      <Dialog open={isOpen} onOpenChange={(open) => { if (!open) closeDialog() }}>
        <DialogContent className="max-w-sm">
          <DialogHeader>
            <DialogTitle>
              {dialog.mode === "create" ? t("dialogCreateUser") : t("dialogEditUser")}
            </DialogTitle>
          </DialogHeader>

          <div className="flex flex-col gap-3">
            <FormField label={t("fieldUsername")}>
              <Input
                autoFocus
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                placeholder="username"
                readOnly={dialog.mode === "edit"}
              />
            </FormField>

            <FormField label={t("fieldPassword")}>
              <Input
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder={dialog.mode === "create" ? t("fieldPasswordPlaceholder") : ""}
              />
              {dialog.mode === "edit" && (
                <p className="text-xs text-muted-foreground">{t("fieldPasswordEditHint")}</p>
              )}
            </FormField>

            <FormField label={t("fieldDisplayName")}>
              <Input
                value={displayName}
                onChange={(e) => setDisplayName(e.target.value)}
                placeholder={t("fieldDisplayName")}
              />
            </FormField>

            <FormField label={t("fieldEmail")}>
              <Input
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="user@example.com"
              />
            </FormField>
          </div>

          <DialogFooter>
            <Button variant="ghost" onClick={closeDialog}>{t("cancel")}</Button>
            <Button onClick={submit}>
              {dialog.mode === "create" ? t("submitCreate") : t("submitUpdate")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}

/* ------------------------------------------------------------------ */
/*  Roles Tab                                                          */
/* ------------------------------------------------------------------ */

function RolesTab() {
  const t = useTranslations("settingsView")
  const { items, loading, create, update, remove } = useCrud<Role>("/api/roles")
  const [dialog, setDialog] = useState<RoleDialogState>({ mode: "closed" })

  const [code, setCode] = useState("")
  const [name, setName] = useState("")
  const [description, setDescription] = useState("")

  const closeDialog = useCallback(() => {
    setDialog({ mode: "closed" })
    setCode(""); setName(""); setDescription("")
  }, [])

  const openCreate = () => {
    setCode(""); setName(""); setDescription("")
    setDialog({ mode: "create" })
  }

  const openEdit = (r: Role) => {
    setCode(r.code)
    setName(r.name)
    setDescription(r.description ?? "")
    setDialog({ mode: "edit", id: r.id })
  }

  const submit = async () => {
    if (dialog.mode === "create") {
      if (!code.trim() || !name.trim()) return
      await create({ code, name, description })
    } else if (dialog.mode === "edit") {
      await update(dialog.id, { name, description })
    }
    closeDialog()
  }

  if (loading) return <p className="p-4 text-sm text-muted-foreground">{t("loading")}</p>

  const isOpen = dialog.mode !== "closed"

  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center justify-between">
        <h2 className="font-serif text-lg font-semibold">{t("rolesTitle")}</h2>
        <Button size="sm" onClick={openCreate}>
          {t("addRole")}
        </Button>
      </div>

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
                    onClick={() => openEdit(r)}
                  >
                    {t("edit")}
                  </Button>
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

      {/* ---- Dialog ---- */}
      <Dialog open={isOpen} onOpenChange={(open) => { if (!open) closeDialog() }}>
        <DialogContent className="max-w-sm">
          <DialogHeader>
            <DialogTitle>
              {dialog.mode === "create" ? t("dialogCreateRole") : t("dialogEditRole")}
            </DialogTitle>
          </DialogHeader>

          <div className="flex flex-col gap-3">
            <FormField label={t("fieldRoleCode")}>
              <Input
                autoFocus
                value={code}
                onChange={(e) => setCode(e.target.value)}
                placeholder="DEVELOPER"
                readOnly={dialog.mode === "edit"}
              />
            </FormField>

            <FormField label={t("fieldRoleName")}>
              <Input
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder={t("fieldRoleNamePlaceholder")}
              />
            </FormField>

            <FormField label={t("fieldDescription")}>
              <Input
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                placeholder={t("fieldRoleDescPlaceholder")}
              />
            </FormField>
          </div>

          <DialogFooter>
            <Button variant="ghost" onClick={closeDialog}>{t("cancel")}</Button>
            <Button onClick={submit}>
              {dialog.mode === "create" ? t("submitCreate") : t("submitUpdate")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}

/* ------------------------------------------------------------------ */
/*  Projects Tab                                                       */
/* ------------------------------------------------------------------ */

function ProjectsTab() {
  const t = useTranslations("settingsView")
  const { items, loading, create, update, remove } = useCrud<Project>("/api/projects")
  const [dialog, setDialog] = useState<ProjectDialogState>({ mode: "closed" })

  const [code, setCode] = useState("")
  const [name, setName] = useState("")

  const closeDialog = useCallback(() => {
    setDialog({ mode: "closed" })
    setCode(""); setName("")
  }, [])

  const openCreate = () => {
    setCode(""); setName("")
    setDialog({ mode: "create" })
  }

  const openEdit = (p: Project) => {
    setCode(p.code)
    setName(p.name)
    setDialog({ mode: "edit", id: p.id })
  }

  const submit = async () => {
    if (dialog.mode === "create") {
      if (!code.trim() || !name.trim()) return
      await create({ code, name })
    } else if (dialog.mode === "edit") {
      await update(dialog.id, { name })
    }
    closeDialog()
  }

  if (loading) return <p className="p-4 text-sm text-muted-foreground">{t("loading")}</p>

  const isOpen = dialog.mode !== "closed"

  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center justify-between">
        <h2 className="font-serif text-lg font-semibold">{t("projectsTitle")}</h2>
        <Button size="sm" onClick={openCreate}>
          {t("addProject")}
        </Button>
      </div>

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
                    onClick={() => openEdit(p)}
                  >
                    {t("edit")}
                  </Button>
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

      {/* ---- Dialog ---- */}
      <Dialog open={isOpen} onOpenChange={(open) => { if (!open) closeDialog() }}>
        <DialogContent className="max-w-sm">
          <DialogHeader>
            <DialogTitle>
              {dialog.mode === "create" ? t("dialogCreateProject") : t("dialogEditProject")}
            </DialogTitle>
          </DialogHeader>

          <div className="flex flex-col gap-3">
            <FormField label={t("fieldProjectCode")}>
              <Input
                autoFocus
                value={code}
                onChange={(e) => setCode(e.target.value)}
                placeholder="my-project"
                readOnly={dialog.mode === "edit"}
              />
            </FormField>

            <FormField label={t("fieldProjectName")}>
              <Input
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder={t("fieldProjectNamePlaceholder")}
              />
            </FormField>
          </div>

          <DialogFooter>
            <Button variant="ghost" onClick={closeDialog}>{t("cancel")}</Button>
            <Button onClick={submit}>
              {dialog.mode === "create" ? t("submitCreate") : t("submitUpdate")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
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
