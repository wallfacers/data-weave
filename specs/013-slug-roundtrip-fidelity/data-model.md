# Data Model: slug / 身份模型（013）

本特性不引入持久化实体、不改 schema。"模型"指**派生身份**——名字如何确定性地映射到本地文件名与往返身份键。

## 实体：EntitySlug（派生，非持久）

承载实体（任务/工作流/目录/标签）在本地文件树中的标识，兼"文件路径段"与"往返身份键"双职。

| 属性 | 含义 | 规则 |
|---|---|---|
| `base` | 名字派生的基础 slug | `name.toLowerCase()` 后将非 `[a-z0-9_-]` 连续段折为 `_`，压缩重复 `_`，去首尾 `_` |
| `degenerate?` | 是否退化 | `base` 不含任何 `[a-z0-9]` ⇒ 退化（**判定改点**：原为 `base.isEmpty()`） |
| `fallback` | 退化回落值 | `e` + SHA-256(name, UTF-8) 前若干字节 hex（确定性，CL-001 纯哈希） |
| `effective` | 退化前的有效 slug | 退化 ⇒ `fallback`；否则 `base` |
| `final` | 唯一性守卫后的最终 slug | 同目录兄弟集内若 `effective` 撞名 ⇒ 按实体 id 升序对第 2 个起追加确定性后缀 |

### 不变量（MUST）

- **INV-1 可移植**：`final` 仅含 `[a-z0-9_-]`（`SlugRules.PORTABLE`），非保留名（`SlugRules.RESERVED`）。
- **INV-2 同目录唯一**：同一目录路径下任意两实体的 `final` 不相等（杜绝文件覆盖/身份坍缩）。
- **INV-3 确定性**：同一 (name, 同目录兄弟集, id) 永远产出同一 `final`；不依赖随机/时钟/插入序。
- **INV-4 导出=身份一致**：导出落地文件名用的 `final` 与 diff/push 身份匹配用的 `final` 由**同一工具**产出，二者必然相等（无漂移）。
- **INV-5 跨 pull 稳定**：服务器实体不变时，重复 pull 得到完全相同的 `final` 集合。

### 派生流水线

```
name ──base──▶ effective ──(同目录兄弟集 + id 排序)──▶ final ──▶ <final>.task.yaml / .flow.yaml / 目录名
              （退化则 fallback）          （唯一性守卫）
                                                  └────▶ diff/push 身份键 (catalogPath, final)
```

## 关系与适用面

- 适用所有以 slug 为身份的实体类型：TaskDef、WorkflowDef、CatalogNode（目录名）、Tag。
- 唯一性守卫的"兄弟集"= 同一 `catalogPath` 下的同类实体集合。
- 单一真相源：派生 + 退化 + 唯一性守卫工具置于 `filecontract/naming`（与 `SlugRules` 同处）。`ProjectSyncService.slugify`、`ProjectMapper.derive*Slug` 均改为调用之。

## 状态/迁移

无运行期状态机。唯一"迁移"语义：本修复使既有中文名实体的文件名从退化 `-`/`-gmv` 变为哈希 `e<...>`；发布前无历史副本需兼容（spec Assumptions）。

## 验证映射（FR/SC → 不变量）

| 需求 | 由不变量保障 |
|---|---|
| FR-001 / FR-006 / SC-001（拉取无损、数=数） | INV-2 |
| FR-002（退化判定） | `degenerate?` 改点 |
| FR-003 / SC-004（确定性消歧） | INV-2 + INV-3 |
| FR-004（导出=身份一致） | INV-4 |
| FR-005 / SC-003（差异忠实不失明） | INV-2 + INV-4（不再对称坍缩） |
| FR-007 / SC-002 / SC-005（往返身份稳定、不误删） | INV-3 + INV-4 + INV-5 |
| FR-008 / FR-012（可移植、不依赖随机） | INV-1 + INV-3 |
| FR-009（全实体类型一致） | 单一真相源 + 适用面 |
