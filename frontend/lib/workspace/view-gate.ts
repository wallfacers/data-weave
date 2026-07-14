import type { ProjectPermissionStatus } from "@/lib/project-permissions"

export type ViewGate = "loading" | "denied" | "allow"

/**
 * 视图级权限闸门判定（纯函数，便于单测）。
 *
 * 关键：项目权限是异步加载的（GET /api/projects/{id}/me），加载完成前 membership 为
 * null → can 恒 false。此时**不能**断言「拒绝」，否则刷新（CTRL+R）由 persistence
 * 恢复上次激活的受限视图（如「系统设置」requirePermission=project:manage）时，会在权限
 * 就绪前闪现、甚至（/me 慢或失败时）长期停在居中的「权限不足」。
 *
 * 故 idle/loading 期返回 loading 占位，仅在权限确定就绪后才判：
 * - ready 且持有 → allow；ready 但不持有 → denied；
 * - error（/me 失败，membership 落 EMPTY_MEMBERSHIP）→ 无法核验，保守 denied。
 */
export function resolveViewGate(
  requirePermission: string | undefined,
  status: ProjectPermissionStatus,
  can: (permission: string) => boolean,
): ViewGate {
  if (!requirePermission) return "allow"
  if (status === "idle" || status === "loading") return "loading"
  return can(requirePermission) ? "allow" : "denied"
}
