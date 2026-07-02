"use client";

import { useState, useEffect, useCallback } from "react";
import { useTranslations } from "next-intl";
import { HugeiconsIcon } from "@hugeicons/react";
import type { IconSvgElement } from "@hugeicons/react";
import {
  Shield01Icon,
  Alert02Icon,
  ChartEvaluationIcon,
  RefreshIcon,
} from "@hugeicons/core-free-icons"
import { LoadingState } from "@/components/workspace/shared/loading-state";
import { authFetch, API_BASE } from "@/lib/types";
import { useLiveData } from "@/lib/workspace/use-api";
import type { ViewProps } from "@/lib/workspace/registry";
import { ViewRefreshControl } from "./view-refresh-control";

/** 质量断言规则 */
interface QualityRule {
  id: number;
  name: string;
  assertionType: string;
  severity: string;
  action: string;
  datasetRef: string;
  enabled: number;
  scheduleCron?: string;
}

/** 执行历史 */
interface QualityCheckRun {
  id: number;
  trigger: string;
  status: string;
  datasetRef: string;
  ruleCount: number;
  failCount: number;
  blocked: number;
  sampled: number;
  startedAt?: string;
  finishedAt?: string;
  durationMs?: number;
}

/** 单断言结果 */
interface QualityCheckResult {
  id: number;
  runId: number;
  ruleId: number;
  assertionType: string;
  status: string;
  measuredValue?: string;
  expected?: string;
  message?: string;
  sampled: number;
}

/** 评分卡 */
interface QualityScorecard {
  id: number;
  datasetRef: string;
  score: number;
  passRate: string;
  totalChecks: number;
  failedChecks: number;
  computedAt?: string;
}

type TabKey = "rules" | "runs" | "scorecards";

const STATUS_COLORS: Record<string, string> = {
  PASS: "text-success",
  FAIL: "text-destructive",
  WARN: "text-warning",
  ERROR: "text-muted-foreground",
  RUNNING: "text-info",
};

function triggerLabel(trigger: string, t: ReturnType<typeof import("next-intl").useTranslations>): string {
  switch (trigger) {
    case "POST_TASK": return t("triggerPostTask")
    case "SCHEDULED": return t("triggerScheduled")
    case "ON_DEMAND": return t("triggerOnDemand")
    default: return trigger
  }
}

function qualityStatusLabel(status: string, t: ReturnType<typeof import("next-intl").useTranslations>): string {
  switch (status) {
    case "PASS": return t("statusPass")
    case "FAIL": return t("statusFail")
    case "WARN": return t("statusWarn")
    case "ERROR": return t("statusError")
    case "RUNNING": return t("statusRunning")
    default: return status
  }
}

export function QualityView({ active }: ViewProps) {
  const t = useTranslations("qualityView");

  const [tab, setTab] = useState<TabKey>("rules");
  const [selectedRun, setSelectedRun] = useState<QualityCheckRun | null>(null);
  const [results, setResults] = useState<QualityCheckResult[]>([]);
  const [error, setError] = useState<string | null>(null);

  const fetchJson = useCallback(async <T,>(path: string): Promise<T> => {
    const res = await authFetch(API_BASE + path);
    const json = await res.json();
    if (json.code !== 0 || !json.data) {
      throw new Error(json.message || t("fetchError"));
    }
    return json.data as T;
  }, [t]);

  // tab data via useLiveData（deps: [tab]）
  const tabFetcher = useCallback(async () => {
    setError(null);
    switch (tab) {
      case "rules": return await fetchJson<QualityRule[]>("/api/quality/rules")
      case "runs": return await fetchJson<QualityCheckRun[]>("/api/quality/runs")
      case "scorecards": return await fetchJson<QualityScorecard[]>("/api/quality/scorecards")
      default: return [] as unknown as QualityRule[]
    }
  }, [tab, fetchJson])

  const [autoEnabled, setAutoEnabled] = useState(true)

  const {
    data: tabData,
    loading,
    refreshing,
    stale,
    lastUpdatedAt,
    refresh,
  } = useLiveData<QualityRule[] | QualityCheckRun[] | QualityScorecard[]>(
    tabFetcher,
    { active, enabled: autoEnabled, deps: [tab] },
  )

  const rules = (tab === "rules" ? tabData ?? [] : []) as QualityRule[]
  const runs = (tab === "runs" ? tabData ?? [] : []) as QualityCheckRun[]
  const scorecards = (tab === "scorecards" ? tabData ?? [] : []) as QualityScorecard[]

  const loadResults = useCallback(async (runId: number) => {
    setError(null);
    try {
      const data = await fetchJson<QualityCheckResult[]>(
        `/api/quality/runs/${runId}/results`,
      );
      setResults(data);
    } catch (e) {
      setError((e as Error).message || t("fetchError"));
    }
  }, [fetchJson, t]);

  const triggerRun = useCallback(async (ruleId: number) => {
    setError(null);
    const res = await authFetch(API_BASE + `/api/quality/rules/${ruleId}/run`, {
      method: "POST",
    });
    const json = await res.json();
    if (json.code === 0) {
      refresh();
    } else {
      setError(json.message || t("fetchError"));
    }
  }, [refresh, t]);

  const selectRun = (run: QualityCheckRun) => {
    setSelectedRun(run);
    loadResults(run.id);
  };

  const TAB_ITEMS: { key: TabKey; labelKey: string; icon: IconSvgElement }[] = [
    { key: "rules", labelKey: "tabRules", icon: Shield01Icon },
    { key: "runs", labelKey: "tabRuns", icon: Alert02Icon },
    { key: "scorecards", labelKey: "tabScorecards", icon: ChartEvaluationIcon },
  ];

  return (
    <div className="flex flex-col h-full">
      {/* Tab bar — 与运维中心 OpsTabBar 风格一致 */}
      <div role="tablist">
        <div className="flex items-center gap-1 px-5 h-11">
          {TAB_ITEMS.map((item) => {
            const isActive = tab === item.key
            return (
              <button
                key={item.key}
                type="button"
                role="tab"
                aria-selected={isActive}
                onClick={() => { setTab(item.key); setSelectedRun(null); setResults([]); }}
                className={
                  "relative flex items-center gap-1.5 px-3 py-1 text-sm transition-colors "
                  + (isActive
                    ? "font-medium text-foreground after:absolute after:inset-x-2 after:bottom-0 after:h-0.5 after:rounded-full after:bg-primary"
                    : "text-muted-foreground hover:text-foreground")
                }
              >
                <HugeiconsIcon icon={item.icon} className="size-4" />
                {t(item.labelKey as never)}
              </button>
            )
          })}
        </div>
        <div className="mx-5 border-b" />
      </div>

      {/* Toolbar */}
      <div className="flex items-center justify-end px-5 py-2">
        <ViewRefreshControl
          lastUpdatedAt={lastUpdatedAt}
          refreshing={refreshing}
          stale={stale}
          autoEnabled={autoEnabled}
          onToggleAuto={setAutoEnabled}
          onRefresh={refresh}
        />
      </div>

      {/* content */}
      <div className="flex min-h-0 flex-1 flex-col overflow-auto p-5">
        {error && (
          <div className="text-sm text-destructive mb-3 p-3 bg-destructive/10 rounded shrink-0">
            {error}
          </div>
        )}
        {loading && tabData == null && (
          <LoadingState active={loading} />
        )}

        {!loading && tab === "rules" && (
          <div className="flex flex-1 flex-col space-y-1">
            {rules.length === 0 && (
              <div className="flex flex-1 flex-col items-center justify-center gap-2 text-muted-foreground">
                <HugeiconsIcon icon={Shield01Icon} size={32} />
                <p className="text-sm">{t("emptyRules")}</p>
              </div>
            )}
            {rules.map((r) => (
              <div
                key={r.id}
                className="flex items-center justify-between p-3 rounded border bg-card text-sm"
              >
                <div className="flex items-center gap-3 min-w-0">
                  <span className="font-medium truncate max-w-48">{r.name}</span>
                  <span className="text-xs px-1.5 py-0.5 rounded bg-muted">
                    {r.assertionType}
                  </span>
                  <span className={`text-xs font-medium ${STATUS_COLORS[r.action === "BLOCK" ? "FAIL" : "WARN"]}`}>
                    {r.action}
                  </span>
                  <span className="text-xs text-muted-foreground truncate max-w-64">
                    {r.datasetRef}
                  </span>
                </div>
                <button
                  onClick={() => triggerRun(r.id)}
                  className="inline-flex items-center gap-1 px-2 py-1 rounded text-xs font-medium bg-primary/10 text-primary hover:bg-primary/20 transition-colors shrink-0"
                >
                  <HugeiconsIcon icon={RefreshIcon} className="size-3" />
                  {t("checkNow")}
                </button>
              </div>
            ))}
          </div>
        )}

        {!loading && tab === "runs" && (
          <div className="flex flex-1 gap-4">
            <div className="flex flex-1 flex-col space-y-1">
              {runs.length === 0 && (
                <div className="flex flex-1 flex-col items-center justify-center gap-2 text-muted-foreground">
                  <HugeiconsIcon icon={Alert02Icon} size={32} />
                  <p className="text-sm">{t("emptyRuns")}</p>
                </div>
              )}
              {runs.map((r) => (
                <div
                  key={r.id}
                  onClick={() => selectRun(r)}
                  className={
                    "flex items-center justify-between p-3 rounded border text-sm cursor-pointer transition-colors "
                    + (selectedRun?.id === r.id
                      ? "bg-primary/5 border-primary/30"
                      : "bg-card hover:bg-muted/50")
                  }
                >
                  <div className="flex items-center gap-3 min-w-0">
                    <span className={`text-xs font-medium ${STATUS_COLORS[r.status] || ""}`}>
                      {qualityStatusLabel(r.status, t)}
                    </span>
                    <span className="text-xs text-muted-foreground">
                      {triggerLabel(r.trigger, t)}
                    </span>
                    <span className="text-xs text-muted-foreground truncate max-w-48">
                      {r.datasetRef}
                    </span>
                    <span className="text-xs">
                      {r.failCount}/{r.ruleCount} {t("failUnit")}
                    </span>
                    {r.blocked === 1 && (
                      <span className="text-xs px-1 py-0.5 rounded bg-destructive/10 text-destructive">
                        {t("blocked")}
                      </span>
                    )}
                    {r.sampled === 1 && (
                      <span className="text-xs px-1 py-0.5 rounded bg-warning/10 text-warning">
                        {t("sampled")}
                      </span>
                    )}
                  </div>
                  <span className="text-xs text-muted-foreground shrink-0">
                    {r.finishedAt ? r.finishedAt.slice(0, 16).replace("T", " ") : ""}
                  </span>
                </div>
              ))}
            </div>

            {/* result detail panel */}
            {selectedRun && (
              <div className="w-80 shrink-0 border rounded p-3 space-y-1.5 bg-card">
                <h4 className="text-sm font-medium">
                  {t("resultDetail")} #{selectedRun.id}
                </h4>
                {results.length === 0 && (
                  <p className="text-xs text-muted-foreground">{t("emptyResults")}</p>
                )}
                {results.map((r) => (
                  <div
                    key={r.id}
                    className="p-2 rounded bg-muted/50 text-xs space-y-0.5"
                  >
                    <div className="flex items-center gap-2">
                      <span className={`font-medium ${STATUS_COLORS[r.status] || ""}`}>
                        {qualityStatusLabel(r.status, t)}
                      </span>
                      <span>{r.assertionType}</span>
                      {r.sampled === 1 && (
                        <span className="text-warning">{t("sampled")}</span>
                      )}
                    </div>
                    {r.measuredValue && (
                      <div className="text-muted-foreground">
                        {t("measuredLabel")}: {r.measuredValue}
                        {r.expected && <> / {t("expectedLabel")}: {r.expected}</>}
                      </div>
                    )}
                    {r.message && (
                      <div className="text-muted-foreground">{r.message}</div>
                    )}
                  </div>
                ))}
              </div>
            )}
          </div>
        )}

        {!loading && tab === "scorecards" && (
          <div className="flex flex-1 flex-col space-y-3">
            {scorecards.length === 0 && (
              <div className="flex flex-1 flex-col items-center justify-center gap-2 text-muted-foreground">
                <HugeiconsIcon icon={ChartEvaluationIcon} size={32} />
                <p className="text-sm">{t("emptyScorecards")}</p>
              </div>
            )}
            {scorecards.map((s) => (
              <div
                key={s.id}
                className="flex items-center justify-between p-3 rounded border bg-card text-sm"
              >
                <div className="flex items-center gap-4">
                  <span className="text-2xl font-bold">
                    {s.score}
                  </span>
                  <div className="space-y-0.5">
                    <div className="font-medium truncate max-w-64">{s.datasetRef}</div>
                    <div className="text-xs text-muted-foreground">
                      {t("passRateLabel")}: {s.passRate} · {t("checksLabel")}: {s.totalChecks}
                      {s.failedChecks > 0 && <> · {t("failedLabel")}: {s.failedChecks}</>}
                    </div>
                  </div>
                </div>
                <span className="text-xs text-muted-foreground">
                  {s.computedAt ? s.computedAt.slice(0, 16).replace("T", " ") : ""}
                </span>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
