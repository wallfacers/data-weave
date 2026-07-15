"use client"

import { ViewContainer } from "@/components/ui/view-container"
import { LoadingState } from "@/components/workspace/shared/loading-state"

/**
 * 虚拟管家视图 —— 空壳（T007）。
 * US1-US4 逐步填充：形象场景、汇报卡片栈、对话、治理面板。
 */
export function CompanionView() {
  return (
    <ViewContainer>
      <LoadingState />
    </ViewContainer>
  )
}
