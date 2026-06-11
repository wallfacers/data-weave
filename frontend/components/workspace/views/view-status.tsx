"use client"

import { API_BASE } from "@/lib/types"

/** 视图统一的加载/失败占位 */
export function ViewStatus({ loading }: { loading: boolean }) {
  return (
    <div className="flex flex-1 items-center justify-center p-10 text-center">
      <p className="text-sm text-muted-foreground">
        {loading ? "加载中…" : `无法连接后端（${API_BASE}），请确认后端已启动。`}
      </p>
    </div>
  )
}
