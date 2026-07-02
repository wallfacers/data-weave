"use client";

import { useState, useEffect } from "react";
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

// ─── helpers ────────────────────────────────────────────────────

function handleGate(r: GateResult, verb: string) {
  switch (r.outcome) {
    case "EXECUTED":
      toast.success(verb);
      return true;
    case "PENDING_APPROVAL":
      toast.info(r.message ?? "Submitted for approval");
      return true;
    default:
      toast.error(r.message ?? "Action failed");
      return false;
  }
}

// ─── component ──────────────────────────────────────────────────

export function AssetDialog({ open, mode, asset, datasources, onClose, onSaved }: AssetDialogProps) {
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
  const title = isCreate ? "编目资产" : "编辑资产";

  async function doSave() {
    if (!qualifiedName.trim()) {
      toast.error("限定名不能为空");
      return;
    }
    if (isCreate && !datasourceId) {
      toast.error("请选择数据源");
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
      if (tags.trim()) body.tags = tags.split(",").map((t) => t.trim()).filter(Boolean);

      let res: { data: GateResult };
      if (isCreate) {
        body.datasourceId = datasourceId;
        res = await createAsset(body);
      } else {
        res = await updateAsset(asset!.id!, body);
      }

      if (handleGate(res.data, isCreate ? "资产已编目" : "资产已更新")) {
        onSaved();
        onClose();
      }
    } catch {
      toast.error("操作失败，请稍后重试");
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
            {isCreate ? "填写资产元数据，限定名在同数据源下唯一" : "仅修改被变更的字段，未触及字段不变"}
          </DialogDescription>
        </DialogHeader>

        <div className="grid gap-4 py-4">
          {isCreate && (
            <div className="grid grid-cols-4 items-center gap-4">
              <label className="text-right text-sm font-medium">数据源</label>
              <DropdownSelect
                className="col-span-3"
                value={datasourceId?.toString() ?? ""}
                onChange={(v) => setDatasourceId(v ? Number(v) : undefined)}
                options={(datasources ?? []).map((d) => ({ value: String(d.id), label: d.name }))}
              />
            </div>
          )}

          <div className="grid grid-cols-4 items-center gap-4">
            <label className="text-right text-sm font-medium">限定名 *</label>
            <Input
              className="col-span-3"
              value={qualifiedName}
              onChange={(e) => setQualifiedName(e.target.value)}
              placeholder="schema.table_name"
              disabled={!isCreate}
            />
          </div>

          <div className="grid grid-cols-4 items-center gap-4">
            <label className="text-right text-sm font-medium">名称</label>
            <Input className="col-span-3" value={name} onChange={(e) => setName(e.target.value)} placeholder="可读名称" />
          </div>

          <div className="grid grid-cols-4 items-center gap-4">
            <label className="text-right text-sm font-medium">描述</label>
            <Input className="col-span-3" value={description} onChange={(e) => setDescription(e.target.value)} placeholder="简要描述" />
          </div>

          <div className="grid grid-cols-4 items-center gap-4">
            <label className="text-right text-sm font-medium">负责人 ID</label>
            <Input className="col-span-3" value={ownerId} onChange={(e) => setOwnerId(e.target.value)} placeholder="数字" />
          </div>

          <div className="grid grid-cols-4 items-center gap-4">
            <label className="text-right text-sm font-medium">管家 ID</label>
            <Input className="col-span-3" value={stewardId} onChange={(e) => setStewardId(e.target.value)} placeholder="数字" />
          </div>

          <div className="grid grid-cols-4 items-center gap-4">
            <label className="text-right text-sm font-medium">敏感度</label>
            <DropdownSelect
              className="col-span-3"
              value={sensitivity}
              onChange={(v) => setSensitivity((v || "INTERNAL") as Sensitivity)}
              options={SENSITIVITY_OPTIONS}
              disableClear
            />
          </div>

          <div className="grid grid-cols-4 items-center gap-4">
            <label className="text-right text-sm font-medium">标签</label>
            <Input className="col-span-3" value={tags} onChange={(e) => setTags(e.target.value)} placeholder="逗号分隔" />
          </div>
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={onClose} disabled={saving}>取消</Button>
          <Button onClick={doSave} disabled={saving}>{saving ? "提交中…" : "提交"}</Button>
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
  const [loading, setLoading] = useState(false);

  return (
    <Dialog open={open} onOpenChange={(o) => { if (!o) onClose(); }}>
      <DialogContent className="sm:max-w-sm">
        <DialogHeader>
          <DialogTitle>下线资产</DialogTitle>
          <DialogDescription>
            确认下线 {assetName ?? "此资产"}？下线后状态变为 RETIRED，不可逆转。
          </DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <Button variant="outline" onClick={onClose} disabled={loading}>取消</Button>
          <Button variant="destructive" onClick={() => { setLoading(true); onConfirm(); }} disabled={loading}>
            {loading ? "处理中…" : "确认下线"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
