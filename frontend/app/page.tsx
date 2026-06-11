import { Suspense } from "react"

import { Workspace } from "@/components/workspace/workspace"

/** 根路由即 Workspace；useSearchParams（深链消费）需要 Suspense 包裹 */
export default function HomePage() {
  return (
    <Suspense fallback={null}>
      <Workspace />
    </Suspense>
  )
}
