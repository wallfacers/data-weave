# Research: slug 唯一性与中文名健壮性（013）

所有 NEEDS CLARIFICATION 已在 spec/clarify 解决；本文件固化根因证据与修法决策。

## R1. 根因（已实测，非推断）

**Decision**: 缺陷 = `ProjectSyncService.slugify()` 的退化判定错误 + 无唯一性守卫。

**证据链**（2026-06-28 E2E 闭环真跑，H2 后端 + 真 dw）：
- `slugify(name)`：`lower.replaceAll("[^a-z0-9_-]+","_").replaceAll("_+","_").replaceAll("^_|_$","")`；**保留连字符 `-`**。哈希回落仅在结果 `isEmpty()` 时触发。
- 中文「类别-描述」名：`抽取-拉取订单分区`→`-`、`指标-GMV 汇总`→`-gmv`；非空 → 不回落 → 多名同 slug。
- 实测 demo（project 1）：服务端 **20** 任务，pull bundle 仅 **16** 文件（`20-4=16`）；6 个 ECHO 任务（id10-15）里 id10/11/12/14/15 全 slug→`-`，`Map<path,content>` 同键覆盖只剩 id15；id13→`-gmv` 幸存。
- diff/push 身份键 `(catalogPath, slug)` 用同一 slugify → 服务端 `HashMap` 也坍缩 → `dw diff` 对丢 4 任务的副本报"完全一致"= **失明**。

**关键结构洞察**：`slugify()` 经 `ProjectSyncService` L481/485 装配 `taskSlugs/workflowSlugs` map，该 map 既喂**导出文件名**（`ProjectMapper` 经 `export.taskSlugs()` 取用）又喂**diff/push 身份键**（L276-340）。⇒ **slugify 是导出与身份的共同单一上游**，修它一处即同时修两侧（这也是为何实测文件名是 `-` 而非 `deriveTaskSlug` 的空串——后者是几乎不命中的回落）。

## R2. 退化判定：isEmpty → 无 [a-z0-9]

**Decision**: slug "退化"判定改为"派生结果不含任何 `[a-z0-9]` 字母数字字符"即视为退化，回落哈希。

**Rationale**: `-`/`-gmv` 中 `-gmv` 含 `gmv` 不算退化（但仍可能与他名撞，交由 R4 唯一性守卫），而 `-`/`--`/`_` 这类纯标点必须判退化。以"有无区分字符"为准，根除"非空但无区分度"漏网。

**Alternatives rejected**: 继续 isEmpty 判定（=现状 bug）；剥离连字符后再判空（治标，`指标-gmv` 仍漏）。

## R3. 退化回落方案：纯确定性哈希（CL-001）

**Decision**: 退化名 → `e<hashN>`（名字 UTF-8 的 SHA-256 前若干字节 hex，沿用现有空串回落同款 `e`+hex 形态）。

**Rationale**: ① 与现有空串回落一致、与 bundle 中既存哈希命名文件（如 `e1702eedc.task.yaml`）惯例一致；② 确定性、可复现、零新依赖；③ 可读性由文件内 `name:` 承担。CL-001 三选一已定 A。

**Alternatives rejected**: ASCII 残基+哈希（`-gmv` 残基误导、规则复杂）；拼音转写（依赖 + 多音字 + scope 爆炸，违 YAGNI）。

## R4. 唯一性守卫：同目录撞名确定性消歧

**Decision**: 在**已知同目录兄弟集合**处（导出装配 + 身份匹配两侧共用同一工具），对派生出相同 slug 的实体，按**稳定排序键（实体 id 升序）**追加确定性后缀（如 `-<id>` 或末位序号），保证同目录内 slug 两两唯一且跨 pull 稳定。

**Rationale**: `slugify` 单名孤立无法感知兄弟，碰撞只能在"知道全部兄弟"的装配处解决。R2+R3 已消灭绝大多数碰撞（不同中文名→不同哈希）；残余碰撞源 = 完全同名实体或哈希极小概率撞，守卫兜底。稳定 id 排序保证导出与身份两侧对同一实体追加同一后缀 → 无漂移。

**Alternatives rejected**: 运行期序号/插入序（不确定、跨 pull 漂移）；随机后缀（违"不依赖随机"约束）。

## R5. 单一真相源：收敛三处重复实现

**Decision**: slug 派生 + 退化 + 唯一性守卫统一到 `filecontract/naming`（与 `SlugRules` 同处）。`ProjectSyncService.slugify` 与 `ProjectMapper.deriveTaskSlug/deriveWorkflowSlug` 改为调用同一工具，删除各自正则副本。

**Rationale**: 现有三份近似实现（slugify、deriveTaskSlug、deriveWorkflowSlug）是身份漂移温床；constitution 原则 V"复用不重写"+ 单一真相源。`SlugRules.checkCaseCollisions` 已是同目录唯一性雏形，可扩展承载通用守卫。

**Alternatives rejected**: 各处分别打补丁（漂移风险，违 DRY）。

## R6. 一致性适用面 + 范围边界

**Decision**: 唯一性/退化规则适用所有以 slug 为身份的实体类型（任务/工作流/目录/标签）。不改同步 API 端点契约与传输形态；发布前无历史 slug 迁移（种子数据，文件名变化可接受）。

**Rationale**: spec Edge Cases + FR-009。目录/标签若沿用同款派生亦受同病影响，需一并覆盖；范围克制避免 scope 蔓延。

## R7. CLI 按 code 拉取契约缝（US4，已实现）

**Decision**: `cli/sync/resolve.go` 读分页 `data.items[]`（平台惯例，ProjectController.list/User/Role 一致），非 `content`；mock 夹具改回真契约 `items`。

**Rationale**: 实测 `dw pull demo` 此前永远失败；mock 用 `content` 自证错契约故单测假绿。已改并真验通过（`dw pull demo` → 成功）。
