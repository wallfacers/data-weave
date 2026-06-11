import { redirect } from "next/navigation"

/** 旧路由深链兜底：跳转 Workspace 并打开对应视图 */
export default function Page() {
  redirect("/?open=quality")
}
