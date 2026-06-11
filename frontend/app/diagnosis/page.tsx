import { redirect } from "next/navigation"

/** 旧路由深链兜底：跳转 Workspace 并打开诊断视图（透传 instanceId） */
export default async function Page({
  searchParams,
}: {
  searchParams: Promise<{ instanceId?: string }>
}) {
  const params = await searchParams
  redirect(
    params.instanceId
      ? `/?open=diagnosis&instanceId=${encodeURIComponent(params.instanceId)}`
      : "/?open=diagnosis",
  )
}
