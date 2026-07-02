"use client"

import { useCallback, useEffect, useMemo, useState, type ReactNode } from "react"
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  Edit02Icon,
  Delete02Icon,
} from "@hugeicons/core-free-icons"
import { useApi } from "@/lib/auth"
import type { ApiResponse } from "@/lib/types"
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
import { DataTable } from "@/components/ui/data-table"
import { Switch } from "@/components/ui/switch"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"
import { type ColumnDef, type FilterDef, type FetchQuery, type PageResult, toQueryParams } from "@/lib/data-table"

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
/*  Generic fetcher helper for server-mode DataTable                    */
/* ------------------------------------------------------------------ */

async function fetchPage<T>(
  basePath: string,
  query: FetchQuery,
  defs: FilterDef[],
  api: (path: string, init?: RequestInit) => Promise<Response>,
): Promise<PageResult<T>> {
  const qs = toQueryParams(query, defs)
  const res = await api(`${basePath}?${qs.toString()}`)
  const json = (await res.json()) as ApiResponse<unknown>
  if (json.code !== 0) throw new Error(json.message || "API error")
  const data = json.data as Record<string, unknown> | T[]
  if (Array.isArray(data)) {
    const arr = data as T[]
    return { items: arr, total: arr.length, page: 1, size: arr.length }
  }
  return {
    items: (data.items ?? data.content ?? []) as T[],
    total: (data.total ?? data.totalElements ?? 0) as number,
    page: (data.page ?? (data.number != null ? (data.number as number) + 1 : 1)) as number,
    size: (data.size ?? 20) as number,
  }
}

/* ------------------------------------------------------------------ */
/*  Shared form-field helper                                           */
/* ------------------------------------------------------------------ */

function FormField({
  label,
  children,
}: {
  label: string
  children: ReactNode
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
  const api = useApi()
  const [dialog, setDialog] = useState<UserDialogState>({ mode: "closed" })
  const [reloadSignal, setReloadSignal] = useState(0)
  const reload = useCallback(() => setReloadSignal((n) => n + 1), [])

  /* form fields */
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
      await api("/api/users", {
        method: "POST",
        body: JSON.stringify({ username, password, displayName, email }),
      })
    } else if (dialog.mode === "edit") {
      const body: Record<string, string> = { username, displayName, email }
      if (password.trim()) body.password = password
      await api(`/api/users/${dialog.id}`, {
        method: "PUT",
        body: JSON.stringify(body),
      })
    }
    reload()
    closeDialog()
  }

  const toggleStatus = async (u: User) => {
    const newStatus = u.status === "ACTIVE" ? "DISABLED" : "ACTIVE"
    await api(`/api/users/${u.id}`, {
      method: "PUT",
      body: JSON.stringify({ status: newStatus }),
    })
    reload()
  }

  const deleteUser = async (id: number) => {
    await api(`/api/users/${id}`, { method: "DELETE" })
    reload()
  }

  // Filters
  const filters = useMemo<FilterDef[]>(() => [
    {
      key: "search",
      label: t("filterSearchUser"),
      kind: "search",
      placeholder: t("filterSearchUser"),
      width: "w-56",
    },
    {
      key: "status",
      label: t("filterStatus"),
      kind: "segmented",
      options: [
        { value: "ACTIVE", label: t("statusActive") },
        { value: "DISABLED", label: t("statusDisabled") },
      ],
    },
  ], [t])

  const fetcher = useCallback(
    (q: FetchQuery) => fetchPage<User>("/api/users", q, filters, api),
    [filters, api],
  )

  const columns = useMemo<ColumnDef<User>[]>(() => [
    { key: "username", header: t("colUsername"), widthPct: 18, cellClassName: "font-mono text-xs" },
    { key: "displayName", header: t("colDisplayName"), widthPct: 18 },
    { key: "email", header: t("colEmail"), widthPct: 28, cell: (row) => <span className="text-muted-foreground">{row.email || "—"}</span> },
    {
      key: "status",
      header: t("colStatus"),
      widthPct: 12,
      cell: (row) => (
        <Badge variant={row.status === "ACTIVE" ? "success" : "destructive"}>
          {row.status === "ACTIVE" ? t("statusActive") : t("statusDisabled")}
        </Badge>
      ),
    },
    {
      key: "actions",
      header: t("colActions"),
      widthPct: 14,
      align: "right",
      cell: (row) => (
        <div className="flex justify-end items-center gap-0.5">
          <Tooltip>
            <TooltipTrigger
              render={
                <Button variant="ghost" size="icon" className="size-7" onClick={() => openEdit(row)}>
                  <HugeiconsIcon icon={Edit02Icon} className="size-3.5" />
                </Button>
              }
            />
            <TooltipContent>{t("edit")}</TooltipContent>
          </Tooltip>
          <Tooltip>
            <TooltipTrigger className="flex">
              <Switch
                checked={row.status === "ACTIVE"}
                onCheckedChange={async () => { await toggleStatus(row) }}
              />
            </TooltipTrigger>
            <TooltipContent>{row.status === "ACTIVE" ? t("disable") : t("enable")}</TooltipContent>
          </Tooltip>
          <Tooltip>
            <TooltipTrigger
              render={
                <Button variant="ghost" size="icon" className="size-7" onClick={() => { void deleteUser(row.id) }}>
                  <HugeiconsIcon icon={Delete02Icon} className="size-3.5 text-destructive" />
                </Button>
              }
            />
            <TooltipContent>{t("delete")}</TooltipContent>
          </Tooltip>
        </div>
      ),
    },
  ], [t])

  const isOpen = dialog.mode !== "closed"

  return (
    <div className="flex min-h-0 min-w-0 flex-1 flex-col">
      <DataTable<User>
        columns={columns}
        getRowId={(r) => String(r.id)}
        mode="server"
        fetcher={fetcher}
        filters={filters}
        reloadSignal={reloadSignal}
        toolbarActions={
          <Button size="sm" onClick={openCreate}>{t("addUser")}</Button>
        }
        emptyTitle={t("emptyTitleUsers")}
        pageSize={15}
      />

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
  const api = useApi()
  const [dialog, setDialog] = useState<RoleDialogState>({ mode: "closed" })
  const [reloadSignal, setReloadSignal] = useState(0)
  const reload = useCallback(() => setReloadSignal((n) => n + 1), [])

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
      await api("/api/roles", {
        method: "POST",
        body: JSON.stringify({ code, name, description }),
      })
    } else if (dialog.mode === "edit") {
      await api(`/api/roles/${dialog.id}`, {
        method: "PUT",
        body: JSON.stringify({ name, description }),
      })
    }
    reload()
    closeDialog()
  }

  const deleteRole = async (id: number) => {
    await api(`/api/roles/${id}`, { method: "DELETE" })
    reload()
  }

  // Filters: only search (克制)
  const filters = useMemo<FilterDef[]>(() => [
    {
      key: "search",
      label: t("filterSearchRole"),
      kind: "search",
      placeholder: t("filterSearchRole"),
      width: "w-48",
    },
  ], [t])

  const fetcher = useCallback(
    (q: FetchQuery) => fetchPage<Role>("/api/roles", q, filters, api),
    [filters, api],
  )

  const columns = useMemo<ColumnDef<Role>[]>(() => [
    { key: "code", header: t("colCode"), widthPct: 22, cellClassName: "font-mono text-xs" },
    { key: "name", header: t("colName"), widthPct: 22 },
    { key: "description", header: t("colDescription"), widthPct: 44, cell: (row) => <span className="text-muted-foreground">{row.description || "—"}</span> },
    {
      key: "actions",
      header: t("colActions"),
      widthPct: 12,
      align: "right",
      cell: (row) => (
        <div className="flex justify-end items-center gap-0.5">
          <Tooltip>
            <TooltipTrigger
              render={
                <Button variant="ghost" size="icon" className="size-7" onClick={() => openEdit(row)}>
                  <HugeiconsIcon icon={Edit02Icon} className="size-3.5" />
                </Button>
              }
            />
            <TooltipContent>{t("edit")}</TooltipContent>
          </Tooltip>
          <Tooltip>
            <TooltipTrigger
              render={
                <Button variant="ghost" size="icon" className="size-7" onClick={() => { void deleteRole(row.id) }}>
                  <HugeiconsIcon icon={Delete02Icon} className="size-3.5 text-destructive" />
                </Button>
              }
            />
            <TooltipContent>{t("delete")}</TooltipContent>
          </Tooltip>
        </div>
      ),
    },
  ], [t])

  const isOpen = dialog.mode !== "closed"

  return (
    <div className="flex min-h-0 min-w-0 flex-1 flex-col">
      <DataTable<Role>
        columns={columns}
        getRowId={(r) => String(r.id)}
        mode="server"
        fetcher={fetcher}
        filters={filters}
        reloadSignal={reloadSignal}
        toolbarActions={
          <Button size="sm" onClick={openCreate}>{t("addRole")}</Button>
        }
        emptyTitle={t("emptyTitleRoles")}
        pageSize={15}
      />

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
  const api = useApi()
  const [dialog, setDialog] = useState<ProjectDialogState>({ mode: "closed" })
  const [reloadSignal, setReloadSignal] = useState(0)
  const reload = useCallback(() => setReloadSignal((n) => n + 1), [])

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
      await api("/api/projects", {
        method: "POST",
        body: JSON.stringify({ code, name }),
      })
    } else if (dialog.mode === "edit") {
      await api(`/api/projects/${dialog.id}`, {
        method: "PUT",
        body: JSON.stringify({ name }),
      })
    }
    reload()
    closeDialog()
  }

  const deleteProject = async (id: number) => {
    await api(`/api/projects/${id}`, { method: "DELETE" })
    reload()
  }

  // Filters
  const filters = useMemo<FilterDef[]>(() => [
    {
      key: "search",
      label: t("filterSearchProject"),
      kind: "search",
      placeholder: t("filterSearchProject"),
      width: "w-48",
    },
    {
      key: "status",
      label: t("filterStatusProject"),
      kind: "segmented",
      options: [
        { value: "ACTIVE", label: t("statusProjectActive") },
        { value: "ARCHIVED", label: t("statusProjectArchived") },
      ],
    },
  ], [t])

  const fetcher = useCallback(
    (q: FetchQuery) => fetchPage<Project>("/api/projects", q, filters, api),
    [filters, api],
  )

  const columns = useMemo<ColumnDef<Project>[]>(() => [
    { key: "code", header: t("colCode"), widthPct: 24, cellClassName: "font-mono text-xs" },
    { key: "name", header: t("colName"), widthPct: 28 },
    {
      key: "status",
      header: t("colStatus"),
      widthPct: 16,
      cell: (row) => (
        <Badge variant={row.status === "ACTIVE" ? "success" : "secondary"}>
          {row.status === "ACTIVE" ? t("statusProjectActive") : (row.status === "ARCHIVED" ? t("statusProjectArchived") : row.status)}
        </Badge>
      ),
    },
    {
      key: "actions",
      header: t("colActions"),
      widthPct: 12,
      align: "right",
      cell: (row) => (
        <div className="flex justify-end items-center gap-0.5">
          <Tooltip>
            <TooltipTrigger
              render={
                <Button variant="ghost" size="icon" className="size-7" onClick={() => openEdit(row)}>
                  <HugeiconsIcon icon={Edit02Icon} className="size-3.5" />
                </Button>
              }
            />
            <TooltipContent>{t("edit")}</TooltipContent>
          </Tooltip>
          <Tooltip>
            <TooltipTrigger
              render={
                <Button variant="ghost" size="icon" className="size-7" onClick={() => { void deleteProject(row.id) }}>
                  <HugeiconsIcon icon={Delete02Icon} className="size-3.5 text-destructive" />
                </Button>
              }
            />
            <TooltipContent>{t("delete")}</TooltipContent>
          </Tooltip>
        </div>
      ),
    },
  ], [t])

  const isOpen = dialog.mode !== "closed"

  return (
    <div className="flex min-h-0 min-w-0 flex-1 flex-col">
      <DataTable<Project>
        columns={columns}
        getRowId={(r) => String(r.id)}
        mode="server"
        fetcher={fetcher}
        filters={filters}
        reloadSignal={reloadSignal}
        toolbarActions={
          <Button size="sm" onClick={openCreate}>{t("addProject")}</Button>
        }
        emptyTitle={t("emptyTitleProjects")}
        pageSize={15}
      />

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
    <div className="flex flex-1 flex-col gap-5 p-5">
      {/* Tab 条 — 下划线式 */}
      <div className="flex items-center gap-1 border-b h-11" role="tablist">
        {TABS.map((tabItem) => {
          const isActive = tab === tabItem.key
          return (
            <button
              key={tabItem.key}
              type="button"
              role="tab"
              aria-selected={isActive}
              onClick={() => setTab(tabItem.key)}
              className={
                "relative flex items-center gap-1.5 px-3 py-1 text-sm transition-colors " +
                (isActive
                  ? "font-medium text-foreground after:absolute after:inset-x-2 after:bottom-0 after:h-0.5 after:rounded-full after:bg-primary"
                  : "text-muted-foreground hover:text-foreground")
              }
            >
              {t(tabItem.labelKey)}
            </button>
          )
        })}
      </div>

      {/* 内容 */}
      {tab === "users" && <UsersTab />}
      {tab === "roles" && <RolesTab />}
      {tab === "projects" && <ProjectsTab />}
    </div>
  )
}
