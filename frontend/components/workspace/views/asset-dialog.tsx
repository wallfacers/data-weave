"use client";

import { useState, useEffect } from "react";
import { useTranslations } from "next-intl";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogFooter,
  DialogDescription,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { DropdownSelect } from "@/components/ui/select";

import {
  createAsset,
  updateAsset,
  Sensitivity,
  GateResult,
} from "@/lib/catalog-api";

// ─── types ──────────────────────────────────────────────────────

export interface AssetDialogProps {
  open: boolean;
  mode: "create" | "edit";
  /** Pre-fill for edit mode */
  asset?: Partial<{
    id: number;
    datasourceId: number;
    qualifiedName: string;
    name: string;
    description: string;
    ownerId: number;
    stewardId: number;
    sensitivity: Sensitivity;
    tags: string[];
  }>;
  /** Available datasources for the create form */
  datasources?: { id: number; name: string }[];
  onClose: () => void;
  /** Called after successful create/edit to refresh the list */
  onSaved: () => void;
}

const SENSITIVITY_OPTIONS = [
  { value: "PUBLIC", label: "PUBLIC" },
  { value: "INTERNAL", label: "INTERNAL" },
  { value: "CONFIDENTIAL", label: "CONFIDENTIAL" },
  { value: "PII", label: "PII" },
];

// ─── component ──────────────────────────────────────────────────

export function AssetDialog({ open, mode, asset, datasources, onClose, onSaved }: AssetDialogProps) {
  const t = useTranslations("assetCatalog");
  const [saving, setSaving] = useState(false);
  const [datasourceId, setDatasourceId] = useState<number | undefined>(asset?.datasourceId);
  const [qualifiedName, setQualifiedName] = useState(asset?.qualifiedName ?? "");
  const [name, setName] = useState(asset?.name ?? "");
  const [description, setDescription] = useState(asset?.description ?? "");
  const [ownerId, setOwnerId] = useState(asset?.ownerId?.toString() ?? "");
  const [stewardId, setStewardId] = useState(asset?.stewardId?.toString() ?? "");
  const [sensitivity, setSensitivity] = useState<Sensitivity>(asset?.sensitivity ?? "INTERNAL");
  const [tags, setTags] = useState(asset?.tags?.join(", ") ?? "");

  // Reset on open
  useEffect(() => {
    if (!open) return;
    setDatasourceId(asset?.datasourceId);
    setQualifiedName(asset?.qualifiedName ?? "");
    setName(asset?.name ?? "");
    setDescription(asset?.description ?? "");
    setOwnerId(asset?.ownerId?.toString() ?? "");
    setStewardId(asset?.stewardId?.toString() ?? "");
    setSensitivity(asset?.sensitivity ?? "INTERNAL");
    setTags(asset?.tags?.join(", ") ?? "");
  }, [open, asset]);

  const isCreate = mode === "create";
  const title = isCreate ? t("createTitle") : t("editTitle");

  // 写操作闸门三态 → toast（成功用动词；待审批/失败回落后端 message，再无则通用文案）。
  function handleGate(r: GateResult, successVerb: string) {
    switch (r.outcome) {
      case "EXECUTED":
        toast.success(successVerb);
        return true;
      case "PENDING_APPROVAL":
        toast.info(r.message ?? t("pendingApproval"));
        return true;
      default:
        toast.error(r.message ?? t("actionFailedGeneric"));
        return false;
    }
  }

  async function doSave() {
    if (!qualifiedName.trim()) {
      toast.error(t("errAssetInvalid"));
      return;
    }
    if (isCreate && !datasourceId) {
      toast.error(t("formDatasourceRequired"));
      return;
    }
    setSaving(true);
    try {
      const body: Record<string, unknown> = {
        qualifiedName: qualifiedName.trim(),
        name: name.trim() || undefined,
        description: description.trim() || undefined,
        sensitivity,
      };
      if (ownerId) body.ownerId = Number(ownerId);
      if (stewardId) body.stewardId = Number(stewardId);
      if (tags.trim()) body.tags = tags.split(",").map((tag) => tag.trim()).filter(Boolean);

      let res: { data: GateResult };
      if (isCreate) {
        body.datasourceId = datasourceId;
        res = await createAsset(body);
      } else {
        res = await updateAsset(asset!.id!, body);
      }

      if (handleGate(res.data, isCreate ? t("createdToast") : t("updatedToast"))) {
        onSaved();
        onClose();
      }
    } catch {
      toast.error(t("saveFailed"));
    } finally {
      setSaving(false);
    }
  }

  return (
    <Dialog open={open} onOpenChange={(o) => { if (!o) onClose(); }}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription>
            {isCreate ? t("createDesc") : t("editDesc")}
          </DialogDescription>
        </DialogHeader>

        <div className="grid gap-4 py-4">
          {isCreate && (
            <div className="grid grid-cols-4 items-center gap-4">
              <label className="text-right text-sm font-medium">{t("datasource")}</label>
              <DropdownSelect
                className="col-span-3"
                value={datasourceId?.toString() ?? ""}
                onChange={(v) => setDatasourceId(v ? Number(v) : undefined)}
                options={(datasources ?? []).map((d) => ({ value: String(d.id), label: d.name }))}
              />
            </div>
          )}

          <div className="grid grid-cols-4 items-center gap-4">
            <label className="text-right text-sm font-medium">{t("qualifiedName")} *</label>
            <Input
              className="col-span-3"
              value={qualifiedName}
              onChange={(e) => setQualifiedName(e.target.value)}
              placeholder="schema.table_name"
              disabled={!isCreate}
            />
          </div>

          <div className="grid grid-cols-4 items-center gap-4">
            <label className="text-right text-sm font-medium">{t("name")}</label>
            <Input className="col-span-3" value={name} onChange={(e) => setName(e.target.value)} placeholder={t("namePlaceholder")} />
          </div>

          <div className="grid grid-cols-4 items-center gap-4">
            <label className="text-right text-sm font-medium">{t("descriptionLabel")}</label>
            <Input className="col-span-3" value={description} onChange={(e) => setDescription(e.target.value)} placeholder={t("descriptionPlaceholder")} />
          </div>

          <div className="grid grid-cols-4 items-center gap-4">
            <label className="text-right text-sm font-medium">{t("ownerIdLabel")}</label>
            <Input className="col-span-3" value={ownerId} onChange={(e) => setOwnerId(e.target.value)} placeholder={t("numberPlaceholder")} />
          </div>

          <div className="grid grid-cols-4 items-center gap-4">
            <label className="text-right text-sm font-medium">{t("stewardIdLabel")}</label>
            <Input className="col-span-3" value={stewardId} onChange={(e) => setStewardId(e.target.value)} placeholder={t("numberPlaceholder")} />
          </div>

          <div className="grid grid-cols-4 items-center gap-4">
            <label className="text-right text-sm font-medium">{t("sensitivity")}</label>
            <DropdownSelect
              className="col-span-3"
              value={sensitivity}
              onChange={(v) => setSensitivity((v || "INTERNAL") as Sensitivity)}
              options={SENSITIVITY_OPTIONS}
              disableClear
            />
          </div>

          <div className="grid grid-cols-4 items-center gap-4">
            <label className="text-right text-sm font-medium">{t("tagsLabel")}</label>
            <Input className="col-span-3" value={tags} onChange={(e) => setTags(e.target.value)} placeholder={t("tagsPlaceholder")} />
          </div>
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={onClose} disabled={saving}>{t("cancel")}</Button>
          <Button onClick={doSave} disabled={saving}>{saving ? t("submitting") : t("submit")}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** 下线确认对话框 */
export function RetireConfirmDialog({
  open,
  assetName,
  onClose,
  onConfirm,
}: {
  open: boolean;
  assetName?: string;
  onClose: () => void;
  onConfirm: () => void;
}) {
  const t = useTranslations("assetCatalog");
  const [loading, setLoading] = useState(false);

  return (
    <Dialog open={open} onOpenChange={(o) => { if (!o) onClose(); }}>
      <DialogContent className="sm:max-w-sm">
        <DialogHeader>
          <DialogTitle>{t("retireDialogTitle")}</DialogTitle>
          <DialogDescription>
            {t("retireDialogDesc", { name: assetName ?? t("thisAsset") })}
          </DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <Button variant="outline" onClick={onClose} disabled={loading}>{t("cancel")}</Button>
          <Button variant="destructive" onClick={() => { setLoading(true); onConfirm(); }} disabled={loading}>
            {loading ? t("processing") : t("confirmRetire")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
