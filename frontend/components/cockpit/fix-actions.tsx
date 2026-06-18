"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { useTranslations } from "next-intl";
import { HugeiconsIcon, type IconSvgElement } from "@hugeicons/react";
import {
  RefreshIcon,
  ArrowDataTransferHorizontalIcon,
  RamMemoryIcon,
  BalanceScaleIcon,
  Loading03Icon,
  CheckmarkCircle01Icon,
  Cancel01Icon,
} from "@hugeicons/core-free-icons";

import { Button } from "@/components/ui/button";
import { API_BASE, authFetch, type ApiResponse, type DiagnosisSuggestion, type FixResult } from "@/lib/types";

const ACTION_ICONS: Record<string, IconSvgElement> = {
  RERUN: RefreshIcon,
  MIGRATE_NODE: ArrowDataTransferHorizontalIcon,
  RERUN_MORE_MEMORY: RamMemoryIcon,
  CAP_NODE_WEIGHT: BalanceScaleIcon,
};

export function FixActions({
  diagnosisId,
  suggestions,
  disabled,
}: {
  diagnosisId: number;
  suggestions: DiagnosisSuggestion[];
  disabled?: boolean;
}) {
  const router = useRouter();
  const t = useTranslations("fixActions");
  const [loading, setLoading] = useState<string | null>(null);
  const [result, setResult] = useState<FixResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function handleFix(action: string) {
    setLoading(action);
    setResult(null);
    setError(null);
    try {
      const res = await authFetch(
        `${API_BASE}/api/diagnosis/${diagnosisId}/fix?action=${encodeURIComponent(action)}`,
        { method: "POST", cache: "no-store" },
      );
      const json = await res.json() as ApiResponse<FixResult>;
      if (json.code !== 0 || !json.data) {
        throw new Error(json.message || t("execFailed"));
      }
      const data = json.data;
      setResult(data);
      if (data.success) {
        // Refresh server component data after a short delay
        setTimeout(() => router.refresh(), 600);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : t("execFailed"));
    } finally {
      setLoading(null);
    }
  }

  if (disabled) return null;

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap gap-2">
        {suggestions.map((s) => {
          const Icon = ACTION_ICONS[s.action] ?? RefreshIcon;
          const isLoading = loading === s.action;
          return (
            <Button
              key={s.action}
              variant="outline"
              size="sm"
              disabled={loading !== null}
              onClick={() => handleFix(s.action)}
            >
              {isLoading ? (
                <HugeiconsIcon
                  icon={Loading03Icon}
                  className="animate-spin"
                  data-icon="inline-start"
                />
              ) : (
                <HugeiconsIcon icon={Icon} data-icon="inline-start" />
              )}
              {s.label}
            </Button>
          );
        })}
      </div>

      {result && (
        <div
          className={`flex items-center gap-2 rounded-lg px-3 py-2 text-sm ${
            result.success
              ? "bg-success/10 text-success"
              : "bg-destructive/10 text-destructive"
          }`}
        >
          <HugeiconsIcon
            icon={result.success ? CheckmarkCircle01Icon : Cancel01Icon}
            className="size-4 shrink-0"
          />
          <span>{result.message}</span>
        </div>
      )}

      {error && (
        <div className="flex items-center gap-2 rounded-lg bg-destructive/10 px-3 py-2 text-sm text-destructive">
          <HugeiconsIcon
            icon={Cancel01Icon}
            className="size-4 shrink-0"
          />
          <span>{error}</span>
        </div>
      )}
    </div>
  );
}
