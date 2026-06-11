"use client"

import { useCallback, useEffect, useState } from "react"
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
          取消
        </Button>
      </div>
    </div>
  )
}

/* ------------------------------------------------------------------ */
/*  Users Tab                                                          */
/* ------------------------------------------------------------------ */

function UsersTab() {
  const { items, loading, create, update, remove } = useCrud<User>("/api/users")
  const [adding, setAdding] = useState(false)

  if (loading) return <p className="p-4 text-sm text-muted-foreground">加载中…</p>

  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center justify-between">
        <h2 className="font-serif text-lg font-semibold">用户管理</h2>
        <Button size="sm" onClick={() => setAdding(true)}>
          新增用户
        </Button>
      </div>

      {adding && (
        <InlineForm
          fields={[
            { key: "username", label: "用户名", placeholder: "username" },
            { key: "password", label: "密码", type: "password", placeholder: "初始密码" },
            { key: "displayName", label: "显示名", placeholder: "显示名" },
            { key: "email", label: "邮箱", placeholder: "user@example.com" },
          ]}
          onSubmit={async (vals) => {
            await create(vals)
            setAdding(false)
          }}
          onCancel={() => setAdding(false)}
          submitLabel="创建"
        />
      )}

      <div className="overflow-x-auto rounded-[var(--radius-lg)] border">
        <table className="w-full text-sm">
          <thead>
            <tr className="bg-muted/50 text-left text-xs text-muted-foreground">
              <th className="px-3 py-2 font-medium">用户名</th>
              <th className="px-3 py-2 font-medium">显示名</th>
              <th className="px-3 py-2 font-medium">邮箱</th>
              <th className="px-3 py-2 font-medium">状态</th>
              <th className="px-3 py-2 font-medium text-right">操作</th>
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
                    {u.status === "ACTIVE" ? "正常" : "禁用"}
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
                    {u.status === "ACTIVE" ? "禁用" : "启用"}
                  </Button>
                  <Button
                    size="xs"
                    variant="ghost"
                    onClick={() => void remove(u.id)}
                  >
                    删除
                  </Button>
                </td>
              </tr>
            ))}
            {items.length === 0 && (
              <tr>
                <td colSpan={5} className="px-3 py-6 text-center text-muted-foreground">
                  暂无用户
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
  const { items, loading, create, remove } = useCrud<Role>("/api/roles")
  const [adding, setAdding] = useState(false)

  if (loading) return <p className="p-4 text-sm text-muted-foreground">加载中…</p>

  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center justify-between">
        <h2 className="font-serif text-lg font-semibold">角色管理</h2>
        <Button size="sm" onClick={() => setAdding(true)}>
          新增角色
        </Button>
      </div>

      {adding && (
        <InlineForm
          fields={[
            { key: "code", label: "角色编码", placeholder: "DEVELOPER" },
            { key: "name", label: "角色名", placeholder: "开发" },
            { key: "description", label: "描述", placeholder: "角色描述" },
          ]}
          onSubmit={async (vals) => {
            await create(vals)
            setAdding(false)
          }}
          onCancel={() => setAdding(false)}
          submitLabel="创建"
        />
      )}

      <div className="overflow-x-auto rounded-[var(--radius-lg)] border">
        <table className="w-full text-sm">
          <thead>
            <tr className="bg-muted/50 text-left text-xs text-muted-foreground">
              <th className="px-3 py-2 font-medium">编码</th>
              <th className="px-3 py-2 font-medium">名称</th>
              <th className="px-3 py-2 font-medium">描述</th>
              <th className="px-3 py-2 font-medium text-right">操作</th>
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
                    删除
                  </Button>
                </td>
              </tr>
            ))}
            {items.length === 0 && (
              <tr>
                <td colSpan={4} className="px-3 py-6 text-center text-muted-foreground">
                  暂无角色
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
  const { items, loading, create, remove } = useCrud<Project>("/api/projects")
  const [adding, setAdding] = useState(false)

  if (loading) return <p className="p-4 text-sm text-muted-foreground">加载中…</p>

  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center justify-between">
        <h2 className="font-serif text-lg font-semibold">项目管理</h2>
        <Button size="sm" onClick={() => setAdding(true)}>
          新增项目
        </Button>
      </div>

      {adding && (
        <InlineForm
          fields={[
            { key: "code", label: "项目编码", placeholder: "my-project" },
            { key: "name", label: "项目名", placeholder: "我的项目" },
          ]}
          onSubmit={async (vals) => {
            await create(vals)
            setAdding(false)
          }}
          onCancel={() => setAdding(false)}
          submitLabel="创建"
        />
      )}

      <div className="overflow-x-auto rounded-[var(--radius-lg)] border">
        <table className="w-full text-sm">
          <thead>
            <tr className="bg-muted/50 text-left text-xs text-muted-foreground">
              <th className="px-3 py-2 font-medium">编码</th>
              <th className="px-3 py-2 font-medium">名称</th>
              <th className="px-3 py-2 font-medium">状态</th>
              <th className="px-3 py-2 font-medium text-right">操作</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border">
            {items.map((p) => (
              <tr key={p.id} className="hover:bg-muted/30">
                <td className="px-3 py-2 font-mono text-xs">{p.code}</td>
                <td className="px-3 py-2">{p.name}</td>
                <td className="px-3 py-2">
                  <Badge variant={p.status === "ACTIVE" ? "success" : "secondary"}>
                    {p.status === "ACTIVE" ? "活跃" : p.status}
                  </Badge>
                </td>
                <td className="px-3 py-2 text-right">
                  <Button
                    size="xs"
                    variant="ghost"
                    onClick={() => void remove(p.id)}
                  >
                    删除
                  </Button>
                </td>
              </tr>
            ))}
            {items.length === 0 && (
              <tr>
                <td colSpan={4} className="px-3 py-6 text-center text-muted-foreground">
                  暂无项目
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

const TABS: { key: Tab; label: string }[] = [
  { key: "users", label: "用户" },
  { key: "roles", label: "角色" },
  { key: "projects", label: "项目" },
]

export function SettingsView() {
  const [tab, setTab] = useState<Tab>("users")

  return (
    <div className="flex flex-1 flex-col gap-4 p-4">
      {/* Tab 条 */}
      <div className="flex gap-1">
        {TABS.map((t) => (
          <Button
            key={t.key}
            variant={tab === t.key ? "secondary" : "ghost"}
            size="sm"
            onClick={() => setTab(t.key)}
          >
            {t.label}
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
