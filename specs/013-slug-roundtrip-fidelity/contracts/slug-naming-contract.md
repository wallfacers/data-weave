# Contract: slug 命名 + pull-list 字段（013）

本特性不新增 REST 端点；此处固化两条"契约"——内部命名契约与既有列表端点字段契约。

## C1. slug 命名契约（内部，导出与身份共用）

**单一函数**（`filecontract/naming`，导出侧与身份侧唯一来源）：

```
slugOf(name) -> base                         # 现有规则：lower + [^a-z0-9_-]+→_ + 压缩 + 去边
isDegenerate(base) -> base 不含 [a-z0-9]      # 改点：取代 isEmpty
fallbackHash(name) -> "e" + hex(SHA-256(name))[:N]
uniquify(effective, siblingsSortedById) -> final   # 同目录撞名按 id 升序追加确定性后缀
```

**契约保证**：
- 输出仅 `[a-z0-9_-]+`，非保留名。
- `(name, siblings, id)` 相同 ⇒ 输出相同（确定性）。
- 同目录兄弟集内输出两两唯一。
- 导出落地文件名 == 该实体 diff/push 身份键中的 slug（同函数产出）。

**适用类型**：TaskDef / WorkflowDef / CatalogNode / Tag。

## C2. pull-list 字段契约（既有端点，对齐修正）

`GET /api/projects?search=<code>&size=<n>` 响应 `data` 分页对象的条目键 = **`items`**（与 `ProjectController.list` / `User` / `Role` 控制器平台惯例一致）。

- CLI `resolve.go` MUST 读 `data.items[]`（此前误读 `data.content[]` → 按 code 解析永远空命中）。
- 测试夹具 MUST 照搬真服务器契约（`items`），不得用 `content` 自证错契约。
- 状态：**已实现**（resolve.go + sync_e2e_test.go 已改，真验 `dw pull demo` 通过）。

## 不变（非目标）

- 同步端点 `/api/projects/{id}/pull|push|diff` 的请求/响应形态、bundle 传输结构、鉴权与权限闸 **不变**。
- 不新增/删除字段；C2 仅是 CLI 侧对齐既有服务器契约。
