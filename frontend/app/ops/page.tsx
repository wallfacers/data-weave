import { redirect } from "next/navigation"

/** 旧路由深链兜底：跳转 Workspace 并打开「数据开发」IDE */
export default function Page() {
  redirect("/?open=workflow-canvas")
}
