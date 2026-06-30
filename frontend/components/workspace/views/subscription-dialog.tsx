"use client";

import { useCallback, useEffect, useState } from "react";
import { toast } from "sonner";
import { useTranslations } from "next-intl";
import { HugeiconsIcon } from "@hugeicons/react";
import { Cancel01Icon } from "@hugeicons/core-free-icons";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
} from "@/components/ui/dialog";
import { listSubscriptions, unsubscribe, type SubscriptionView } from "@/lib/catalog-api";

export function SubscriptionListDialog({
  open,
  onClose,
}: {
  open: boolean;
  onClose: () => void;
}) {
  const t = useTranslations("assetCatalog");
  const [subs, setSubs] = useState<SubscriptionView[]>([]);
  const [loading, setLoading] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await listSubscriptions();
      setSubs(res.data ?? []);
    } catch {
      toast.error(t("loadFailed"));
    } finally {
      setLoading(false);
    }
  }, [t]);

  useEffect(() => {
    if (open) void load();
  }, [open, load]);

  const doUnsubscribe = useCallback(async (id: number) => {
    try {
      const res = await unsubscribe(id);
      if (res.data?.outcome === "EXECUTED") {
        toast.success(t("unsubscribed") ?? "已退订");
        setSubs((prev) => prev.filter((s) => s.id !== id));
      } else if (res.data?.outcome === "PENDING_APPROVAL") {
        toast.info(t("pendingApproval"));
      } else {
        toast.error(res.message ?? t("actionFailed"));
      }
    } catch {
      toast.error(t("actionFailed"));
    }
  }, [t]);

  return (
    <Dialog open={open} onOpenChange={(o) => { if (!o) onClose(); }}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>{t("mySubscriptions") ?? "我的订阅"}</DialogTitle>
          <DialogDescription>{t("subscriptionsHint") ?? "管理您订阅的资产变更通知"}</DialogDescription>
        </DialogHeader>
        <div className="max-h-80 overflow-auto">
          {loading && <div className="py-4 text-center text-sm text-muted-foreground">{t("loading")}</div>}
          {!loading && subs.length === 0 && (
            <div className="py-4 text-center text-sm text-muted-foreground">{t("noSubscriptions") ?? "暂无订阅"}</div>
          )}
          {subs.map((s) => (
            <div key={s.id} className="flex items-center justify-between border-b border-border/50 px-1 py-2">
              <div className="min-w-0 flex-1">
                <div className="truncate text-sm font-medium">{s.targetName ?? `${s.targetType}#${s.targetId}`}</div>
                {s.changeFilter && (
                  <div className="text-xs text-muted-foreground">{s.changeFilter}</div>
                )}
              </div>
              <Button
                variant="ghost"
                size="sm"
                onClick={() => doUnsubscribe(s.id)}
                title={t("unsubscribe") ?? "退订"}
              >
                <HugeiconsIcon icon={Cancel01Icon} className="size-4 text-muted-foreground" />
              </Button>
            </div>
          ))}
        </div>
      </DialogContent>
    </Dialog>
  );
}
