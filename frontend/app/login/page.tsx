"use client"

import { useState, type FormEvent } from "react"
import { useRouter } from "next/navigation"
import { useAuth } from "@/lib/auth"
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Button } from "@/components/ui/button"

/**
 * 登录页 —— 茶墨编辑部风格。
 *
 * 设计约束（遵循 DESIGN.md）：
 * - 暖纸白底 bg-background + 卡片 bg-card 浮起
 * - primary 茶墨色按钮
 * - 衬线标题 font-serif
 * - 圆角跟随 --radius (0.875rem)
 * - 无分割线，靠留白和背景层次区分
 */
export default function LoginPage() {
  const router = useRouter()
  const { login } = useAuth()

  const [username, setUsername] = useState("")
  const [password, setPassword] = useState("")
  const [error, setError] = useState("")
  const [loading, setLoading] = useState(false)

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setError("")
    setLoading(true)

    try {
      await login(username, password)
      router.push("/")
    } catch (err) {
      setError(err instanceof Error ? err.message : "登录失败")
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="flex min-h-svh items-center justify-center bg-background px-4">
      {/* 居中卡片 */}
      <Card className="w-full max-w-sm">
        <CardHeader className="items-center text-center">
          {/* 品牌 */}
          <div className="mb-2 flex size-12 items-center justify-center rounded-[var(--radius-lg)] bg-primary">
            <span className="font-serif text-xl font-bold text-primary-foreground">
              Dw
            </span>
          </div>
          <CardTitle className="font-serif text-xl">
            DataWeave
          </CardTitle>
          <CardDescription className="text-muted-foreground">
            AI 数据中台 · 请登录
          </CardDescription>
        </CardHeader>

        <CardContent>
          <form onSubmit={handleSubmit} className="flex flex-col gap-4">
            {/* 用户名 */}
            <div className="flex flex-col gap-1.5">
              <label htmlFor="username" className="text-sm font-medium text-foreground">
                用户名
              </label>
              <Input
                id="username"
                type="text"
                placeholder="admin"
                autoComplete="username"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                required
                className="bg-background"
              />
            </div>

            {/* 密码 */}
            <div className="flex flex-col gap-1.5">
              <label htmlFor="password" className="text-sm font-medium text-foreground">
                密码
              </label>
              <Input
                id="password"
                type="password"
                placeholder="••••••"
                autoComplete="current-password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                required
                className="bg-background"
              />
            </div>

            {/* 错误提示 */}
            {error && (
              <p className="text-sm text-destructive" role="alert">
                {error}
              </p>
            )}

            {/* 登录按钮 */}
            <Button type="submit" disabled={loading} className="w-full font-serif">
              {loading ? "登录中…" : "登录"}
            </Button>

            {/* 提示 */}
            <p className="text-center text-xs text-muted-foreground">
              默认账号：admin / admin
            </p>
          </form>
        </CardContent>
      </Card>
    </div>
  )
}
