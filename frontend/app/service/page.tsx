import { redirect } from "next/navigation"

/** 旧路由深链兜底：跳转工作区首页（042 收缩，service 视图已移除） */
export default function Page() {
  redirect("/")
}
