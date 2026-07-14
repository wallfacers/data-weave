"use client"

/**
 * 069 US4 监督席·指挥中心（首屏）。T038 总装：
 * 战况播报横幅（实时数字）+ 全量直播 feed（thinking/chip/delta 智能感层）+ 下钻线程自由对话/结构化裁决。
 *
 * 数据层：{@link useIncidentStream} 直连 SSE（snapshot 先发→实时增量）；数字永远来自 store.stats（SC-010）；
 * 战况报告全文与选中事故提案走 REST 拉取。全部动效 motion-safe，prefers-reduced-motion 降级为静态。
 */
import { useCallback, useEffect, useState } from "react"
import { useTranslations } from "next-intl"

import { useWorkspaceStore } from "@/lib/workspace/store"
import type { ViewProps } from "@/lib/workspace/registry"
import { useIncidentStream } from "@/lib/supervision/use-incident-stream"
import { selectFeed, selectLive, selectMessages } from "@/lib/supervision/store"
import * as api from "@/lib/supervision/api"
import type { BriefingView, IncidentDetail } from "@/lib/supervision/types"
import { cn } from "@/lib/utils"
import { BriefingBanner, type FeedFilterValue } from "./supervision/briefing-banner"
import { LiveFeed } from "./supervision/live-feed"
import { IncidentThread } from "./supervision/incident-thread"

export function SupervisionView(_: ViewProps) {
  const t = useTranslations("supervision")
  const openView = useWorkspaceStore((s) => s.open)
  const { state, dispatch } = useIncidentStream()

  const [briefing, setBriefing] = useState<BriefingView | null>(null)
  const [filter, setFilter] = useState<FeedFilterValue>({ kind: "all" })
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [detail, setDetail] = useState<IncidentDetail | null>(null)

  // 战况报告全文（含 report_md）：首挂载拉一次，之后每当直播播报更新（generatedAt 变）再拉。
  const briefingStamp = state.briefing?.generatedAt ?? null
  useEffect(() => {
    let alive = true
    api
      .getBriefing()
      .then((b) => {
        if (alive) setBriefing(b)
      })
      .catch(() => undefined)
    return () => {
      alive = false
    }
  }, [briefingStamp])

  // 选中事故：拉详情（提案）+ 补齐历史消息进 store（之后直播增量自动追加）。
  const selectedInc = selectedId ? state.incidents[selectedId] : undefined
  const selectedVersion = selectedInc?.version ?? -1
  useEffect(() => {
    if (!selectedId) {
      setDetail(null)
      return
    }
    let alive = true
    api
      .getIncidentDetail(selectedId)
      .then((d) => {
        if (alive) setDetail(d)
      })
      .catch(() => undefined)
    api
      .getMessages(selectedId, 0, 500)
      .then((msgs) => {
        if (alive) dispatch({ type: "seedMessages", incidentId: selectedId, messages: msgs })
      })
      .catch(() => undefined)
    return () => {
      alive = false
    }
  }, [selectedId, selectedVersion, dispatch])

  const reload = useCallback(() => {
    if (!selectedId) return
    api
      .getIncidentDetail(selectedId)
      .then(setDetail)
      .catch(() => undefined)
  }, [selectedId])

  const onOpenLog = useCallback(
    (instanceId: string) => openView("instance-log", { instanceId }),
    [openView],
  )

  // 过滤：banner 数字点击 → feed 过滤。pending=只看待处理；state=按状态；all=全部。
  const feedFilter =
    filter.kind === "state" ? { state: filter.state } : filter.kind === "pending" ? {} : {}
  const { pending, rest } = selectFeed(state, feedFilter)
  const shownPending = filter.kind === "state" ? [] : pending
  const shownRest = filter.kind === "state" ? [...pending, ...rest] : rest

  const stats = state.stats
  const summaryLine = state.briefing?.summaryLine ?? briefing?.summaryLine ?? null

  return (
    <div className="flex h-full flex-col gap-2.5 p-2.5">
      <BriefingBanner
        summaryLine={summaryLine}
        stats={stats}
        reportMd={briefing?.reportMd ?? null}
        connected={state.connected}
        activeFilter={filter}
        onFilter={setFilter}
      />

      <div className="flex min-h-0 flex-1 gap-2.5">
        <div className={cn("flex min-w-0 flex-col", selectedId ? "w-2/5" : "flex-1")}>
          <LiveFeed
            pending={shownPending}
            rest={shownRest}
            liveOf={(id) => selectLive(state, id)}
            selectedId={selectedId}
            onSelect={(id) => setSelectedId((cur) => (cur === id ? null : id))}
          />
        </div>

        {selectedId && selectedInc && (
          <div className="min-w-0 flex-1">
            <IncidentThread
              incident={selectedInc}
              proposals={detail?.proposals ?? []}
              messages={selectMessages(state, selectedId)}
              live={selectLive(state, selectedId)}
              onReload={reload}
              onOpenLog={onOpenLog}
            />
          </div>
        )}
      </div>

      {!state.connected && (
        <p className="shrink-0 text-center text-[11px] text-muted-foreground">{t("reconnecting")}</p>
      )}
    </div>
  )
}
